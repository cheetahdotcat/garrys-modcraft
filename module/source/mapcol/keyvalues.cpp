// KeyValues text parser, surface properties and the D-009 dig-material table.
#include <algorithm>
#include <cctype>

#include "internal.hpp"

namespace gmodcraft::mapcol
{
	namespace detail
	{
		std::string Lower(std::string s)
		{
			for (auto& c : s) {
				c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
			}
			return s;
		}

		bool ParseFloat(const std::string& s, std::size_t* pos, double& out)
		{
			std::size_t i = pos ? *pos : 0;
			while (i < s.size() && std::isspace(static_cast<unsigned char>(s[i]))) {
				++i;
			}
			double sign = 1;
			if (i < s.size() && (s[i] == '-' || s[i] == '+')) {
				sign = s[i] == '-' ? -1 : 1;
				++i;
			}
			double      v = 0;
			bool        digits = false;
			while (i < s.size() && std::isdigit(static_cast<unsigned char>(s[i]))) {
				v = v * 10 + (s[i++] - '0');
				digits = true;
			}
			if (i < s.size() && s[i] == '.') {
				++i;
				double scale = 0.1;
				while (i < s.size() && std::isdigit(static_cast<unsigned char>(s[i]))) {
					v += (s[i++] - '0') * scale;
					scale *= 0.1;
					digits = true;
				}
			}
			if (!digits) {
				return false;
			}
			if (i < s.size() && (s[i] == 'e' || s[i] == 'E')) {
				std::size_t j = i + 1;
				int         esign = 1, e = 0;
				if (j < s.size() && (s[j] == '-' || s[j] == '+')) {
					esign = s[j] == '-' ? -1 : 1;
					++j;
				}
				if (j < s.size() && std::isdigit(static_cast<unsigned char>(s[j]))) {
					while (j < s.size() && std::isdigit(static_cast<unsigned char>(s[j]))) {
						e = std::min(e * 10 + (s[j++] - '0'), 400);
					}
					v *= std::pow(10.0, esign * e);
					i = j;
				}
			}
			out = sign * v;
			if (pos) {
				*pos = i;
			}
			return std::isfinite(out);
		}

		bool ParseInt(const std::string& s, std::int64_t& out)
		{
			std::size_t i = 0;
			while (i < s.size() && std::isspace(static_cast<unsigned char>(s[i]))) {
				++i;
			}
			bool neg = false;
			if (i < s.size() && (s[i] == '-' || s[i] == '+')) {
				neg = s[i] == '-';
				++i;
			}
			std::int64_t v = 0;
			bool         digits = false;
			while (i < s.size() && std::isdigit(static_cast<unsigned char>(s[i]))) {
				if (v > (INT64_MAX - 9) / 10) {
					return false;
				}
				v = v * 10 + (s[i++] - '0');
				digits = true;
			}
			out = neg ? -v : v;
			return digits;
		}

		namespace
		{
			constexpr int         kMaxDepth = 64;
			constexpr std::size_t kMaxNodes = 1u << 20;

			bool IEq(const std::string& a, const char* b)
			{
				const std::size_t n = std::strlen(b);
				if (a.size() != n) {
					return false;
				}
				for (std::size_t i = 0; i < n; ++i) {
					if (std::tolower(static_cast<unsigned char>(a[i])) != std::tolower(static_cast<unsigned char>(b[i]))) {
						return false;
					}
				}
				return true;
			}

			struct Lexer
			{
				const char* p;
				const char* end;

				enum Tok
				{
					kEnd,
					kOpen,
					kClose,
					kString,
				};

				void SkipSpaceAndComments()
				{
					while (p < end) {
						const unsigned char c = static_cast<unsigned char>(*p);
						if (c == 0 || std::isspace(c)) {
							++p;
						} else if (c == '/' && p + 1 < end && p[1] == '/') {
							while (p < end && *p != '\n') {
								++p;
							}
						} else {
							break;
						}
					}
				}

				// Returns the token; for kString fills `s` and `quoted`.
				Tok Next(std::string& s, bool& quoted)
				{
					SkipSpaceAndComments();
					if (p >= end) {
						return kEnd;
					}
					if (*p == '{') {
						++p;
						return kOpen;
					}
					if (*p == '}') {
						++p;
						return kClose;
					}
					s.clear();
					if (*p == '"') {
						quoted = true;
						++p;
						while (p < end && *p != '"') {
							if (*p == '\\' && p + 1 < end && (p[1] == '"' || p[1] == '\\')) {
								++p;
							}
							s.push_back(*p++);
						}
						if (p < end) {
							++p;  // closing quote; an unterminated string just ends the text
						}
						return kString;
					}
					quoted = false;
					while (p < end) {
						const unsigned char c = static_cast<unsigned char>(*p);
						if (c == 0 || std::isspace(c) || c == '{' || c == '}' || c == '"') {
							break;
						}
						if (c == '/' && p + 1 < end && p[1] == '/') {
							break;
						}
						s.push_back(*p++);
					}
					return kString;
				}
			};
		}

		const KvNode* KvNode::Find(const char* k) const
		{
			for (const auto& c : children) {
				if (IEq(c.key, k)) {
					return &c;
				}
			}
			return nullptr;
		}

		std::string KvNode::Get(const char* k, const char* def) const
		{
			const KvNode* n = Find(k);
			return n && !n->block ? n->value : std::string(def);
		}

		bool ParseKeyValues(const char* text, std::size_t len, KvNode& root, std::string& err)
		{
			Lexer                 lx{ text, text + len };
			std::vector<KvNode*>  stack{ &root };
			std::size_t           nodes = 0;
			std::string           tok, val;
			bool                  quoted = false, vquoted = false;
			while (true) {
				auto t = lx.Next(tok, quoted);
				if (t == Lexer::kEnd) {
					break;
				}
				if (t == Lexer::kClose) {
					if (stack.size() <= 1) {
						err = "keyvalues: unbalanced '}'";
						return false;
					}
					stack.pop_back();
					continue;
				}
				if (t == Lexer::kOpen) {  // anonymous block
					tok.clear();
				} else if (!quoted && !tok.empty() && tok[0] == '#') {
					// #include / #base "file": the caller concatenates files itself.
					std::string skip;
					bool        q;
					lx.Next(skip, q);
					continue;
				}
				// Optional [$condition] tokens between key and value are ignored.
				if (t == Lexer::kString) {
					auto v = lx.Next(val, vquoted);
					while (v == Lexer::kString && !vquoted && val.size() >= 2 && val.front() == '[' && val.back() == ']') {
						v = lx.Next(val, vquoted);
					}
					if (v == Lexer::kEnd) {
						break;  // a trailing key without value: ignore
					}
					if (v == Lexer::kClose) {
						if (stack.size() <= 1) {
							err = "keyvalues: unbalanced '}'";
							return false;
						}
						stack.pop_back();
						continue;
					}
					t = v == Lexer::kOpen ? Lexer::kOpen : Lexer::kString;
				}
				if (++nodes > kMaxNodes) {
					err = "keyvalues: too many nodes";
					return false;
				}
				KvNode n;
				n.key = tok;
				if (t == Lexer::kOpen) {
					n.block = true;
					if (static_cast<int>(stack.size()) > kMaxDepth) {
						err = "keyvalues: nested too deep";
						return false;
					}
					stack.back()->children.push_back(std::move(n));
					stack.push_back(&stack.back()->children.back());
				} else {
					n.value = val;
					// A [$condition] after the value is ignored too.
					const char* save = lx.p;
					std::string cond;
					bool        cq = false;
					if (lx.Next(cond, cq) == Lexer::kString && !cq && cond.size() >= 2 && cond.front() == '[' && cond.back() == ']') {
						// consumed
					} else {
						lx.p = save;
					}
					stack.back()->children.push_back(std::move(n));
				}
			}
			return true;  // unclosed blocks at the end are tolerated (Valve's parser does too)
		}
	}

	using detail::Lower;

	bool ParseSurfaceProperties(const std::string& text, SurfaceProps& out, std::string& err)
	{
		detail::KvNode root;
		if (!detail::ParseKeyValues(text.data(), text.size(), root, err)) {
			return false;
		}
		for (const auto& b : root.children) {
			if (!b.block || b.key.empty()) {
				continue;
			}
			out.base[Lower(b.key)] = Lower(b.Get("base"));
		}
		return true;
	}

	namespace
	{
		struct DigName
		{
			const char*        name;
			proto::DigMaterial mat;
			bool               diggable;
		};
		// D-009. Exact (lowercase) surface property names; a name not listed walks its "base" chain.
		constexpr DigName kDigNames[] = {
			// not diggable: tool faces, clips, liquids, ladders
			{ "default_silent", proto::kDigNone, false },
			{ "player_control_clip", proto::kDigNone, false },
			{ "clip", proto::kDigNone, false },
			{ "water", proto::kDigNone, false },
			{ "wade", proto::kDigNone, false },
			{ "slime", proto::kDigNone, false },
			{ "quicksand", proto::kDigNone, false },
			{ "ladder", proto::kDigNone, false },
			{ "woodladder", proto::kDigNone, false },
			{ "player", proto::kDigNone, false },
			// earth
			{ "grass", proto::kDigGrass, true },
			{ "dirt", proto::kDigDirt, true },
			{ "mud", proto::kDigMud, true },
			{ "sand", proto::kDigSand, true },
			{ "gravel", proto::kDigGravel, true },
			{ "rock", proto::kDigStone, true },
			{ "boulder", proto::kDigStone, true },
			{ "stone", proto::kDigStone, true },
			// built stone
			{ "concrete", proto::kDigCobble, true },
			{ "brick", proto::kDigCobble, true },
			{ "tile", proto::kDigCobble, true },
			{ "ceiling_tile", proto::kDigCobble, true },
			{ "plaster", proto::kDigCobble, true },
			{ "default", proto::kDigCobble, true },
			// cold
			{ "snow", proto::kDigSnow, true },
			{ "ice", proto::kDigIce, true },
			{ "gmod_ice", proto::kDigIce, true },
			// wood, cloth, organic
			{ "wood", proto::kDigPlanks, true },
			{ "carpet", proto::kDigCloth, true },
			{ "cloth", proto::kDigCloth, true },
			{ "foliage", proto::kDigOrganic, true },
			{ "flesh", proto::kDigOrganic, true },
			{ "watermelon", proto::kDigOrganic, true },
			// metal family (roots without a metal base)
			{ "solidmetal", proto::kDigMetal, true },
			{ "metal", proto::kDigMetal, true },
			{ "metal_box", proto::kDigMetal, true },
			{ "metalgrate", proto::kDigMetal, true },
			{ "chainlink", proto::kDigMetal, true },
			{ "brass_bell_large", proto::kDigMetal, true },
			{ "brass_bell_medium", proto::kDigMetal, true },
			{ "brass_bell_small", proto::kDigMetal, true },
			{ "brass_bell_smallest", proto::kDigMetal, true },
			{ "gm_ps_metaltire", proto::kDigMetal, true },
			{ "glass", proto::kDigGlass, true },
			// plastic / rubber / paper: unknown material (stone); listed so they don't walk to "dirt"
			{ "plastic", proto::kDigNone, true },
			{ "plastic_box", proto::kDigNone, true },
			{ "plastic_barrel", proto::kDigNone, true },
			{ "rubber", proto::kDigNone, true },
			{ "cardboard", proto::kDigNone, true },
			{ "paper", proto::kDigNone, true },
			{ "floatingstandable", proto::kDigNone, true },
		};

		const DigName* FindDigName(const std::string& lower)
		{
			for (const auto& d : kDigNames) {
				if (lower == d.name) {
					return &d;
				}
			}
			return nullptr;
		}
	}

	DigInfo ClassifySurfaceProp(const SurfaceProps& props, const std::string& name)
	{
		std::string cur = Lower(name);
		for (int step = 0; step < 32 && !cur.empty(); ++step) {
			if (const DigName* d = FindDigName(cur)) {
				return DigInfo{ d->mat, d->diggable };
			}
			auto it = props.base.find(cur);
			if (it == props.base.end()) {
				break;
			}
			cur = it->second;
		}
		return DigInfo{};
	}

	std::uint16_t MaterialTable::Intern(const std::string& name)
	{
		std::string key = Lower(name);
		if (key.empty()) {
			key = "default";
		}
		auto it = index_.find(key);
		if (it != index_.end()) {
			return it->second;
		}
		if (list_.size() >= 0xFFFF) {
			return 0;  // absurd input: everything else shares the first entry
		}
		const auto idx = static_cast<std::uint16_t>(list_.size());
		MaterialInfo info;
		info.name = key;
		list_.push_back(info);
		index_.emplace(key, idx);
		return idx;
	}

	void MaterialTable::Resolve(const SurfaceProps& props)
	{
		for (auto& m : list_) {
			const DigInfo d = ClassifySurfaceProp(props, m.name);
			m.dig = d.material;
			m.diggable = d.diggable;
		}
	}
	bool ParseVmtSurfaceProps(const std::string& text, SurfacePropPair& out, std::string& include)
	{
		out = SurfacePropPair{};
		include.clear();
		detail::KvNode      root;
		std::string err;
		if (!detail::ParseKeyValues(text.data(), text.size(), root, err) || root.children.empty() || !root.children[0].block) {
			return false;
		}
		const detail::KvNode& shader = root.children[0];
		auto take = [&](const detail::KvNode& n) {
			if (out.prop.empty()) {
				out.prop = Lower(n.Get("$surfaceprop"));
			}
			if (out.prop2.empty()) {
				out.prop2 = Lower(n.Get("$surfaceprop2"));
			}
		};
		if (Lower(shader.key) == "patch") {
			for (const char* sub : { "replace", "insert" }) {
				if (const detail::KvNode* n = shader.Find(sub)) {
					take(*n);
				}
			}
			if (out.prop.empty()) {
				include = Lower(shader.Get("include"));
			}
			return true;
		}
		take(shader);
		return true;
	}
}

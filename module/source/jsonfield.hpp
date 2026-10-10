// A string field of a small JSON object (the launcher's ~/.config/garrys-modcraft/config.json),
// without a JSON library. Header-only: checked natively by module/test/token_test.cpp.
#pragma once

#include <string>

namespace gc
{
// The string value of a top-level "key" in a small JSON object (the launcher's config file).
// Handles the escapes JSON allows; anything that doesn't parse gives "". Not a JSON parser: it
// finds the first "key" followed by ':' and a string, which is all the config holds.
inline bool JsonSpace(char c)
{
	return c == ' ' || c == '\t' || c == '\n' || c == '\r';
}

inline std::string JsonStringField(const std::string &json, const char *key)
{
	const std::string want = std::string("\"") + key + "\"";
	std::size_t at = 0;
	while ((at = json.find(want, at)) != std::string::npos)
	{
		std::size_t p = at + want.size();
		while (p < json.size() && JsonSpace(json[p]))
			++p;
		if (p >= json.size() || json[p] != ':')
		{
			at = p;
			continue;
		}
		++p;
		while (p < json.size() && JsonSpace(json[p]))
			++p;
		if (p >= json.size() || json[p] != '"')
			return "";
		std::string out;
		for (++p; p < json.size(); ++p)
		{
			char c = json[p];
			if (c == '"')
				return out;
			if (c != '\\')
			{
				out.push_back(c);
				continue;
			}
			if (++p >= json.size())
				return "";
			switch (json[p])
			{
			case '"': out.push_back('"'); break;
			case '\\': out.push_back('\\'); break;
			case '/': out.push_back('/'); break;
			case 'b': out.push_back('\b'); break;
			case 'f': out.push_back('\f'); break;
			case 'n': out.push_back('\n'); break;
			case 'r': out.push_back('\r'); break;
			case 't': out.push_back('\t'); break;
			case 'u':
			{
				if (p + 4 >= json.size())
					return "";
				unsigned v = 0;
				for (int k = 1; k <= 4; ++k)
				{
					char h = json[p + k];
					v <<= 4;
					if (h >= '0' && h <= '9')
						v |= static_cast<unsigned>(h - '0');
					else if (h >= 'a' && h <= 'f')
						v |= static_cast<unsigned>(h - 'a' + 10);
					else if (h >= 'A' && h <= 'F')
						v |= static_cast<unsigned>(h - 'A' + 10);
					else
						return "";
				}
				p += 4;
				if (v >= 0xD800 && v <= 0xDFFF)
					return "";  // surrogate pairs: not in a path we accept
				if (v < 0x80)
					out.push_back(static_cast<char>(v));
				else if (v < 0x800)
				{
					out.push_back(static_cast<char>(0xC0 | (v >> 6)));
					out.push_back(static_cast<char>(0x80 | (v & 0x3F)));
				}
				else
				{
					out.push_back(static_cast<char>(0xE0 | (v >> 12)));
					out.push_back(static_cast<char>(0x80 | ((v >> 6) & 0x3F)));
					out.push_back(static_cast<char>(0x80 | (v & 0x3F)));
				}
				break;
			}
			default: return "";
			}
		}
		return "";
	}
	return "";
}
}  // namespace gc

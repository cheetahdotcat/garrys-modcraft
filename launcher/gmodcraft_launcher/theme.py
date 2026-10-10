"""Light / dark ttk look. "system" follows the desktop where it can tell (GTK_THEME ending in :dark,
GNOME's color-scheme via gsettings), else light; the Settings tab has a toggle."""
import os
import shutil
import subprocess

PALETTES = {
    "light": {"bg": "#f3f3f1", "fg": "#1f1f1f", "field": "#ffffff", "sel": "#5da13c", "selfg": "#ffffff",
              "muted": "#5f5f5f", "border": "#c8c8c4", "accent": "#f08a24", "ok": "#2e9d3a", "bad": "#c62828",
              "warn": "#b8860b", "button": "#e4e4e0"},
    "dark": {"bg": "#232427", "fg": "#e8e8e6", "field": "#2d2f33", "sel": "#4c8a31", "selfg": "#ffffff",
             "muted": "#a0a0a0", "border": "#3d3f44", "accent": "#f08a24", "ok": "#5bc46a", "bad": "#ef5350",
             "warn": "#e0b040", "button": "#34363b"},
}


def system_prefers_dark():
    """True / False, or None when the desktop doesn't say."""
    gtk = os.environ.get("GTK_THEME", "")
    if gtk:
        return gtk.lower().endswith(":dark") or "dark" in gtk.lower()
    gs = shutil.which("gsettings")
    if gs:
        try:
            r = subprocess.run([gs, "get", "org.gnome.desktop.interface", "color-scheme"],
                               capture_output=True, text=True, timeout=1)
            if r.returncode == 0 and r.stdout.strip():
                return "dark" in r.stdout
        except (OSError, subprocess.SubprocessError):
            pass
    return None


def resolve(setting):
    if setting in ("light", "dark"):
        return setting
    return "dark" if system_prefers_dark() else "light"


def apply(root, setting="system"):
    """Style the window; returns the palette used."""
    from tkinter import ttk
    name = resolve(setting)
    c = PALETTES[name]
    st = ttk.Style(root)
    try:
        st.theme_use("clam")
    except Exception:  # noqa: BLE001
        pass
    root.configure(background=c["bg"])
    st.configure(".", background=c["bg"], foreground=c["fg"], fieldbackground=c["field"], bordercolor=c["border"],
                 lightcolor=c["bg"], darkcolor=c["bg"], troughcolor=c["field"], focuscolor=c["accent"],
                 selectbackground=c["sel"], selectforeground=c["selfg"], insertcolor=c["fg"])
    st.configure("TButton", background=c["button"], padding=(10, 4))
    st.map("TButton", background=[("active", c["sel"]), ("disabled", c["bg"])],
           foreground=[("active", c["selfg"]), ("disabled", c["muted"])])
    st.configure("Accent.TButton", background=c["accent"], foreground="#1f1f1f")
    st.map("Accent.TButton", background=[("active", c["sel"]), ("disabled", c["bg"])])
    st.configure("TEntry", fieldbackground=c["field"], foreground=c["fg"])
    st.configure("TNotebook", background=c["bg"], borderwidth=0)
    st.configure("TNotebook.Tab", background=c["button"], foreground=c["fg"], padding=(14, 5))
    st.map("TNotebook.Tab", background=[("selected", c["bg"])], foreground=[("selected", c["accent"])])
    st.configure("Treeview", background=c["field"], fieldbackground=c["field"], foreground=c["fg"], rowheight=22)
    st.map("Treeview", background=[("selected", c["sel"])], foreground=[("selected", c["selfg"])])
    st.configure("Treeview.Heading", background=c["button"], foreground=c["fg"])
    st.configure("Muted.TLabel", foreground=c["muted"])
    st.configure("Title.TLabel", font=("TkDefaultFont", 15, "bold"), foreground=c["fg"])
    st.configure("TLabelframe", background=c["bg"], bordercolor=c["border"])
    st.configure("TLabelframe.Label", background=c["bg"], foreground=c["muted"])
    st.configure("TCheckbutton", background=c["bg"], foreground=c["fg"])
    st.configure("TRadiobutton", background=c["bg"], foreground=c["fg"])
    return dict(c, name=name)

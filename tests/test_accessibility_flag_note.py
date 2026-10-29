"""Keep the TCL accessibility reset diagnosis and its explicit repair behavior accurate (#1047).

Android can leave `enabled_accessibility_services` naming the launcher's `HomeKeyService` while
`accessibility_enabled` reads `0`: on a TCL C6K Pro (`G08`, Android 14) that is what a reboot left
behind, so nothing was bound, the Home button opened Google TV, and the page's two existing cases (a
force stop that empties the list, and a firmware `APP_AUTO_START` denial) did not describe it. The
launcher's own `restore_key_services` does not reach this state either: `ServiceRepair.restore()` writes
`ACCESSIBILITY_ENABLED` only after it has put a missing helper back on the list, and returns early when
the list is already complete.

The tests pin what a reader has to be able to act on - both settings, the dump, the three commands, and
the device it was seen on - while checking that the opt-in `ServiceRepair` path actually handles the
complete-list/accessibility-off state. A misspelled `settings` key or a wrong class name sends a reader
back to "nothing changed".

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import re
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
TROUBLE = ROOT / "docs" / "TROUBLESHOOTING.md"
SERVICE_REPAIR = (ROOT / "launcher" / "app" / "src" / "main" / "java" / "com" / "gathin" / "tvhome"
                  / "ServiceRepair.java")
THEMES_README = ROOT / "themes" / "README.md"
START = "- **The list still names the helper but"
SECTION = "## Accessibility helper enabled but the remote button does nothing"
LINK = re.compile(r"\]\(([^)\s]+)\)")


def bullet(text, start):
    """The markdown bullet that begins with `start`, its continuation lines and any indented block."""
    lines = text.split("\n")
    first = next(i for i, line in enumerate(lines) if line.startswith(start))
    out = [lines[first]]
    for line in lines[first + 1:]:
        if line.startswith("- **") or line.startswith("## "):
            break
        out.append(line)
    return "\n".join(out)


def section(text, heading):
    """The body under a `## ` heading, up to the next `## `."""
    lines = text.split("\n")
    start = next(i for i, line in enumerate(lines) if line.startswith(heading))
    out = []
    for line in lines[start + 1:]:
        if line.startswith("## "):
            break
        out.append(line)
    return "\n".join(out)


def rebinds_complete_list_when_accessibility_is_off(java):
    """True when the opted-in repair cycles a complete list after accessibility resets to off."""
    return ("needsAccessibilityRebind(accessibilityEnabled, allRecordedServicesEnabled)" in java
            and "Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, \"\")" in java
            and "Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)" in java)


class AccessibilityFlagNoteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.trouble_text = TROUBLE.read_text(encoding="utf-8")
        cls.note = bullet(cls.trouble_text, START)
        cls.section_text = section(cls.trouble_text, SECTION)
        cls.java = SERVICE_REPAIR.read_text(encoding="utf-8")
        cls.themes = THEMES_README.read_text(encoding="utf-8")

    def test_bullet_exists_once(self):
        self.assertEqual(self.trouble_text.count(START), 1,
                         "the state must live in one bullet so its two settings cannot drift apart")

    def test_bullet_lives_in_the_accessibility_helper_section(self):
        self.assertIn(START, self.section_text,
                      "a reader who lands on the helper section has to find this state there")

    def test_bullet_names_both_settings_and_the_values(self):
        # The whole point: the enabled list looks right, so only the flag tells the two apart.
        self.assertIn("enabled_accessibility_services", self.note)
        self.assertIn("accessibility_enabled", self.note)
        self.assertIn("`0`", self.note)
        self.assertIn("Bound services:{}", self.note)

    def test_bullet_gives_the_three_commands_verbatim(self):
        for command in ("tools/tv.py 'settings get secure accessibility_enabled'",
                        "tools/tv.py 'settings put secure accessibility_enabled 1'",
                        "tools/tv.py 'dumpsys accessibility'"):
            with self.subTest(command=command):
                self.assertIn(command, self.note)

    def test_bullet_names_the_component_a_reader_will_see(self):
        self.assertIn("com.gathin.tvhome/com.gathin.tvhome.HomeKeyService", self.note)

    def test_bullet_names_the_device_and_build_it_was_seen_on(self):
        self.assertIn("TCL C6K Pro", self.note)
        self.assertIn("`G08`", self.note)
        self.assertIn("Android 14", self.note)

    def test_bullet_links_the_issue(self):
        self.assertIn("[#1047](https://github.com/Gathin23/cleanyourtv/issues/1047)", self.note)

    def test_bullet_scopes_repair_to_the_explicit_boot_option(self):
        self.assertIn("restore_key_services", self.note)
        self.assertIn("ServiceRepair", self.note)

    def test_opt_in_service_repair_rebinds_a_complete_list(self):
        self.assertIn("launch_home_after_boot", self.note)
        self.assertTrue(rebinds_complete_list_when_accessibility_is_off(self.java),
                        "the note describes a saved-list rebind, which must be implemented in ServiceRepair")
        self.assertIn("ENABLED_ACCESSIBILITY_SERVICES", self.java)

    def test_themes_readme_still_scopes_restore_key_services_to_a_force_stop(self):
        row = next(line for line in self.themes.split("\n")
                   if line.startswith("| `restore_key_services`"))
        self.assertIn("force stop", row,
                      "the key's own row is where a theme author reads what it repairs")

    def test_bullet_keeps_the_honest_limits(self):
        # A successful run does not establish that every boot or TCL firmware behaves the same way.
        self.assertIn("that was one cold-boot", self.section_text)
        self.assertIn("not a guarantee across firmware", self.section_text)
        self.assertIn("not established", self.note)

    def test_bullet_is_not_read_as_licence_to_rewrite_the_list(self):
        # The section forbids rewriting the enabled-services list as a blanket fix; this bullet writes
        # one secure setting instead and has to stay compatible with that sentence.
        self.assertIn("do not rewrite the enabled-services list", self.section_text)

    def test_bullet_links_resolve(self):
        broken = [target for target in LINK.findall(self.note)
                  if not target.startswith(("http://", "https://", "#"))
                  and not (TROUBLE.parent / target.split("#", 1)[0]).exists()]
        self.assertEqual(broken, [], "the bullet must not link a page that does not exist")
        for target in LINK.findall(self.note):
            if target.startswith(("http://", "https://")):
                self.assertEqual(target, "https://github.com/Gathin23/cleanyourtv/issues/1047")

    def test_bullet_extraction_stops_at_the_next_bullet(self):
        sample = "\n".join([START + " one", "  two", "- **Next** three"])
        self.assertEqual(bullet(sample, START), START + " one\n  two")

    def test_bullet_extraction_stops_at_the_next_heading(self):
        sample = "\n".join([START + " one", "  two", "## Next section", "text"])
        self.assertEqual(bullet(sample, START), START + " one\n  two")

    def test_bullet_extraction_keeps_an_indented_block(self):
        sample = "\n".join([START + " one", "", "  ```sh", "  tools/tv.py 'x'", "  ```", "", "prose"])
        self.assertIn("tools/tv.py 'x'", bullet(sample, START))

    def test_section_extraction_stops_at_the_next_heading(self):
        sample = "\n".join(["## One", "body", "## Two", "other"])
        self.assertEqual(section(sample, "## One"), "body")

    def test_rebind_check_requires_all_three_operations(self):
        self.assertTrue(rebinds_complete_list_when_accessibility_is_off(
            "needsAccessibilityRebind(accessibilityEnabled, allRecordedServicesEnabled); "
            "Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, \"\"); "
            "Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);"))
        self.assertFalse(rebinds_complete_list_when_accessibility_is_off(
            "needsAccessibilityRebind(accessibilityEnabled, allRecordedServicesEnabled);"))
        self.assertFalse(rebinds_complete_list_when_accessibility_is_off(""))


if __name__ == "__main__":
    unittest.main()

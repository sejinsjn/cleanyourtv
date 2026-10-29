"""Keep accessibility repair opt-in and document its TCL reboot behavior."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


def read(relative):
    path = ROOT / relative
    return path.read_text(encoding="utf-8") if path.is_file() else ""


CONFIG = read("launcher/app/src/main/res/values/config.xml")
MAIN = read("launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java")
MANIFEST = read("launcher/app/src/main/AndroidManifest.xml")
SERVICE_REPAIR = read("launcher/app/src/main/java/com/gathin/tvhome/ServiceRepair.java")
BOOT_RECEIVER = read("launcher/app/src/main/java/com/gathin/tvhome/BootHomeReceiver.java")
HOME_SERVICE = read("launcher/app/src/main/java/com/gathin/tvhome/HomeKeyService.java")
INSTALL_PS = read("launcher/build-and-install-no-home.ps1")
INSTALL_SH = read("launcher/build-and-install-no-home.sh")
TROUBLE = read("docs/TROUBLESHOOTING.md")


class TestServiceRepairWiring(unittest.TestCase):
    def test_feature_defaults_off_and_home_gates_repair(self):
        self.assertIn('<bool name="restore_key_services">false</bool>', CONFIG)
        self.assertIn('<bool name="launch_home_after_boot">false</bool>', CONFIG)
        self.assertIn('if (cfg.restoreKeyServices) ServiceRepair.restore(this);', MAIN)

    def test_boot_home_is_explicitly_opted_in_and_receiver_is_disabled_by_default(self):
        self.assertIn('android:name=".BootHomeReceiver"', MANIFEST)
        self.assertIn('android:enabled="false"', MANIFEST)
        self.assertIn("android.permission.RECEIVE_BOOT_COMPLETED", MANIFEST)
        self.assertIn("BootHomeReceiver.configure(this, BootHomeReceiver.enabledFor(cfg));", MAIN)
        self.assertIn("config.restoreKeyServices, config.launchHomeAfterBoot", BOOT_RECEIVER)
        self.assertIn("ServiceRepair.hasRecordedHomeService(appContext)", BOOT_RECEIVER)
        self.assertIn("new Intent(this, MainActivity.class)", HOME_SERVICE)
        self.assertNotIn("performGlobalAction(GLOBAL_ACTION_HOME)", HOME_SERVICE)
        self.assertIn("AccessibilityEvent.TYPE_WINDOWS_CHANGED", HOME_SERVICE)
        self.assertIn("BOOT_HOME_LAUNCH_DELAY_MS = 1800", HOME_SERVICE)
        self.assertIn("STOCK_HOME_REDIRECT_RETRY_DELAY_MS = 700", HOME_SERVICE)
        self.assertIn("MAX_STOCK_HOME_REDIRECT_ATTEMPTS = 3", HOME_SERVICE)
        self.assertIn("scheduleStockHomeRedirect();", HOME_SERVICE)
        self.assertIn("scheduleStockHomeRedirect(0);", HOME_SERVICE)
        self.assertIn("launchHandler.post(pendingStockHomeRedirect);", HOME_SERVICE)
        self.assertIn("scheduleStockHomeRedirect(STOCK_HOME_REDIRECT_RETRY_DELAY_MS);", HOME_SERVICE)
        self.assertIn("event.getKeyCode() == KeyEvent.KEYCODE_HOME && !stockHome.isEmpty()", HOME_SERVICE)
        self.assertIn("HOME_KEY_DELAY_MS = 250", HOME_SERVICE)
        self.assertIn("isShortPress(event.getDownTime(), event.getEventTime(), ViewConfiguration.getLongPressTimeout())", HOME_SERVICE)
        self.assertIn("redirectHomeKey(event);", HOME_SERVICE)

    def test_tcl_rebind_requires_permission_and_previous_helper_record(self):
        self.assertIn("Manifest.permission.WRITE_SECURE_SETTINGS", SERVICE_REPAIR)
        self.assertIn("containsAllRecordedServices(list, appContext.getPackageName(),", SERVICE_REPAIR)
        self.assertIn("Collections.singleton(HomeKeyService.class.getName())", SERVICE_REPAIR)
        self.assertIn("needsAccessibilityRebind(accessibilityEnabled, allRecordedServicesEnabled)", SERVICE_REPAIR)
        self.assertIn("PENDING_SERVICES", SERVICE_REPAIR)
        self.assertIn("postDelayed", SERVICE_REPAIR)

    def test_no_home_installers_grant_permission_only_on_explicit_opt_in(self):
        self.assertIn("[switch]$RestoreKeyServices", INSTALL_PS)
        self.assertIn("if ($RestoreKeyServices)", INSTALL_PS)
        self.assertIn("[switch]$LaunchHomeAfterBoot", INSTALL_PS)
        self.assertIn("AUTO_START allow", INSTALL_PS)
        self.assertIn("appops write-settings", INSTALL_PS)
        self.assertIn('${RESTORE_KEY_SERVICES:-0}', INSTALL_SH)
        self.assertIn('${LAUNCH_HOME_AFTER_BOOT:-0}', INSTALL_SH)
        self.assertIn("AUTO_START allow", INSTALL_SH)
        self.assertIn("appops write-settings", INSTALL_SH)
        self.assertIn('= 1 ]', INSTALL_SH)

    def test_tcl_repair_limitations_are_documented(self):
        self.assertIn("Some TCL firmware leaves a helper", TROUBLE)
        self.assertIn("launch_home_after_boot", TROUBLE)
        self.assertIn("does not change the system", TROUBLE)
        self.assertIn("Rebinding briefly reconnects every accessibility", TROUBLE)


if __name__ == "__main__":
    unittest.main()

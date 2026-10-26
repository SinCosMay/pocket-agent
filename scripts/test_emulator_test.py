import unittest
from unittest.mock import patch
import emulator_test

class SuiteSafetyTests(unittest.TestCase):
    def test_stateful_classes_cannot_bypass_case_or_account_orchestration(self):
        for cls in ('LifecycleDeviceTest','CodexAuthDeviceTest'):
            with self.subTest(cls=cls), self.assertRaises(SystemExit):
                emulator_test.selected_test_classes([cls],True)
    def test_live_class_needs_explicit_opt_in(self):
        with self.assertRaises(SystemExit):
            emulator_test.selected_test_classes(['LiveProviderDeviceTest'],False)
        self.assertEqual([('LiveProviderDeviceTest',None)],emulator_test.selected_test_classes(['LiveProviderDeviceTest'],True))
    def test_rejected_suite_cannot_install_apks_or_touch_the_device(self):
        with patch('sys.argv',['emulator_test.py','--serial','emulator-5554','--artifacts','/tmp/apks','--class','CodexAuthDeviceTest']),patch.object(emulator_test,'run') as run:
            with self.assertRaises(SystemExit):emulator_test.main()
            run.assert_not_called()

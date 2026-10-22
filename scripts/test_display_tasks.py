"""Run the Android-independent cleanup membership policy on the host JVM."""
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/dev/pocket/agent/DisplayTaskSnapshot.java"


class DisplayTaskPolicyTest(unittest.TestCase):
    def test_cleanup_membership_and_ownership(self):
        harness = r'''
package dev.pocket.agent;
import java.util.Map;
import java.util.Set;
public class DisplayTaskPolicyTest {
    static void equal(Object expected,Object actual) {
        if(!expected.equals(actual))throw new AssertionError("Expected "+expected+", got "+actual);
    }
    public static void main(String[] args) {
        // Redacted shape of the Android 14 / MIUI dump captured on the phone.
        // The last activities header belongs to the worker, but later dump sections
        // contain the user's tasks again. The old parser deleted all of these.
        String dump="""
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
  * Task{aaa #101 type=standard A=12345:example.personal}
    * Task{bbb #102 type=standard rootTaskId=101}
      rootOfTask=true task=Task{aaa #101 type=standard}
Display #4 (activities from top to bottom):
  * Task{ccc #201 type=standard A=12346:example.worker}

ActivityTaskSupervisor state:
  topDisplayFocusedRootTask=Task{aaa #101 type=standard}
  Display: mDisplayId=0 (organized)
      * Task{aaa #101 type=standard}
        * Task{bbb #102 type=standard rootTaskId=101}
  Display: mDisplayId=4 (organized)
      * Task{ccc #201 type=standard}
  mUserRootTaskInFront={}
        (fullscreen) Task{aaa #101 type=standard}
""";
        equal(Set.of(201),DisplayTaskSnapshot.activities(dump).onDisplay(4));
        equal(Set.of(101),DisplayTaskSnapshot.activities(dump).onDisplay(0));
        equal(0,DisplayTaskSnapshot.activities(dump).taskDisplays.get(102));
        equal(Set.of(42),DisplayTaskSnapshot.activities(
            "Display #7 (activities from top to bottom):\n  RootTask #42: type=standard\n").onDisplay(7));
        String empty=dump.replace("  * Task{ccc #201 type=standard A=12346:example.worker}\n", "");
        equal(Set.of(),DisplayTaskSnapshot.activities(empty).onDisplay(4));
        // Changing display order, nested tasks and task references cannot change ownership.
        equal(Set.of(201),DisplayTaskSnapshot.activities(dump.replace(
            "Display #4 (activities from top to bottom):", "Display #7 (activities from top to bottom):")).onDisplay(7));
        equal(Set.of(),DisplayTaskSnapshot.activities("  topDisplayFocusedRootTask=Task{aaa #101}\n").onDisplay(4));

        String stacks="""
RootTask id=101 bounds=[0,0][1080,2460] displayId=0 userId=0
  taskId=101: example.personal/.Main bounds=[0,0][1080,2460]
  taskId=102: example.child/.Main bounds=[0,0][1080,2460]
RootTask id=201 bounds=[0,0][720,1280] displayId=4 userId=0
  taskId=201: example.worker/.Main bounds=[0,0][720,1280]
RootTask id=301 bounds=[0,0][720,1280] displayId=5 userId=0
  taskId=301: example.otherworker/.Main bounds=[0,0][720,1280]
""";
        DisplayTaskSnapshot before=DisplayTaskSnapshot.stacks(stacks);
        equal(Set.of(201),before.onDisplay(4));
        equal(Set.of(),before.onDisplay(6));
        equal(true,before.packageOutside("example.personal",4));
        equal(true,before.packageOutside("example.otherworker",4));
        equal(false,before.packageOutside("example.worker",4));
        equal(0,before.taskDisplays.get(102));
        DisplayTaskSnapshot childMoved=DisplayTaskSnapshot.stacks(stacks+
            "RootTask id=102 bounds=[0,0][720,1280] displayId=4 userId=0\n");
        equal(Set.of(),childMoved.removable(4,before.taskDisplays));
        // A reused personal task now on the worker display must be restored, never removed.
        DisplayTaskSnapshot borrowed=DisplayTaskSnapshot.stacks(stacks.replace("displayId=0", "displayId=4"));
        equal(Set.of(201),borrowed.removable(4,Map.of(101,0,301,5)));
        // A formerly worker-owned task moved to the physical display is no longer deletable.
        DisplayTaskSnapshot moved=DisplayTaskSnapshot.stacks(stacks.replace("displayId=4", "displayId=0"));
        equal(Set.of(),moved.removable(4,Map.of(101,0,301,5)));
        // Unrecognized records fail closed rather than guessing a display from references.
        equal(Set.of(),DisplayTaskSnapshot.stacks("Task{aaa #101 displayId=4}\n").onDisplay(4));
    }
}
'''
        with tempfile.TemporaryDirectory(prefix="display-task-policy-") as temporary:
            directory = pathlib.Path(temporary)
            test = directory / "DisplayTaskPolicyTest.java"
            test.write_text(harness)
            subprocess.run(["javac", "--release", "17", "-d", temporary, str(SOURCE), str(test)], check=True)
            subprocess.run(["java", "-cp", temporary, "dev.pocket.agent.DisplayTaskPolicyTest"], check=True)


if __name__ == "__main__":
    unittest.main()

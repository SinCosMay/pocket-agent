"""Run the Android-independent cleanup membership policy on the host JVM."""
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/dev/pocket/agent/DisplayTaskSnapshot.java"
LEASE_SOURCE = ROOT / "app/src/main/java/dev/pocket/agent/DisplayLeases.java"


class DisplayTaskPolicyTest(unittest.TestCase):
    def test_cleanup_membership_and_ownership(self):
        harness = r'''
package dev.pocket.agent;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
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
        // An outside task may move through physical display 0 before arriving on B.
        Map<Integer,Integer> origins=new LinkedHashMap<>();
        String root="RootTask id=401 bounds=[0,0][720,1280] displayId=";
        DisplayTaskSnapshot.stacks(root+"4 userId=0\n").protectOutside(7,origins);
        equal(4,origins.get(401));
        DisplayTaskSnapshot.stacks(root+"7 userId=0\n").protectOutside(7,origins);
        equal(4,origins.get(401));
        DisplayTaskSnapshot.stacks(root+"0 userId=0\n").protectOutside(7,origins);
        equal(0,origins.get(401));
        DisplayTaskSnapshot.stacks(root+"5 userId=0\n").protectOutside(7,origins);
        equal(5,origins.get(401));
        DisplayTaskSnapshot returned=DisplayTaskSnapshot.stacks(root+"7 userId=0\n");
        returned.protectOutside(7,origins);
        equal(5,origins.get(401));
        equal(Set.of(),returned.removable(7,origins));
        // Provisional lease survives failed native cleanup; other owners are untouched.
        DisplayLeases leases=new DisplayLeases();
        Object generationA=new Object(),generationB=new Object();
        leases.provision("worker-a",7,generationA);
        leases.provision("worker-b",8,generationA);
        equal(true,leases.provisional("worker-a"));
        equal(false,leases.release("worker-a",generationA,id->false));
        equal(2,leases.size());
        equal(7,leases.get("worker-a"));
        equal(true,leases.provisional("worker-a"));
        equal(true,leases.release("worker-a",generationA,id->id==7));
        equal(1,leases.size());
        equal(8,leases.get("worker-b"));
        leases.activate("worker-b",8);
        equal(false,leases.provisional("worker-b"));
        // Runtime shutdown must try every owner's cleanup after the first fails.
        DisplayLeases shutdown=new DisplayLeases();
        shutdown.provision("stuck",10,generationA);
        shutdown.provision("safe",11,generationA);
        shutdown.provision("also-safe",12,generationA);
        List<String> attempts=new ArrayList<>();
        DisplayLeases.releaseIndependently(shutdown.owners(),owner->{
            attempts.add(owner);
            if(owner.equals("stuck"))throw new IllegalStateException("Cannot restore borrowed task");
            return shutdown.release(owner,generationA,id->true);
        });
        equal(List.of("stuck","safe","also-safe"),attempts);
        equal(Set.of("stuck"),shutdown.owners());
        equal(10,shutdown.get("stuck"));
        DisplayLeases.releaseIndependently(shutdown.owners(),owner->shutdown.release(owner,generationA,id->true));
        equal(0,shutdown.size());
        // An earlier runtime cannot retire a replacement's lease for the same owner.
        leases.provision("main",18,generationA);
        equal(true,leases.release("main",generationA,id->true));
        leases.provision("main",19,generationB);
        equal(false,leases.ownedBy("main",generationA));
        equal(true,leases.ownedBy("main",generationB));
        DisplayLeases.releaseIndependently(Set.of("main"),owner->{
            if(leases.ownedBy(owner,generationA))
                return leases.release(owner,generationA,id->true);
            return false;
        });
        equal(19,leases.get("main"));
        try {
            leases.release("main",generationA,id->true);
            throw new AssertionError("A stale generation must not release another runtime");
        } catch(IllegalStateException expected) {}
        leases.clear(); // Binder invalidation also discards the stale generation.
        leases.provision("main",20,generationB);
        equal(false,leases.ownedBy("main",generationA));
        equal(20,leases.get("main"));
        equal(true,leases.release("main",generationB,id->true));
        // Unrecognized records fail closed rather than guessing a display from references.
        equal(Set.of(),DisplayTaskSnapshot.stacks("Task{aaa #101 displayId=4}\n").onDisplay(4));
    }
}
'''
        with tempfile.TemporaryDirectory(prefix="display-task-policy-") as temporary:
            directory = pathlib.Path(temporary)
            test = directory / "DisplayTaskPolicyTest.java"
            test.write_text(harness)
            subprocess.run(["javac", "--release", "17", "-d", temporary, str(SOURCE), str(LEASE_SOURCE), str(test)], check=True)
            subprocess.run(["java", "-cp", temporary, "dev.pocket.agent.DisplayTaskPolicyTest"], check=True)


if __name__ == "__main__":
    unittest.main()

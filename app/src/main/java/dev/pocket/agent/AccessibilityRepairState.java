package dev.pocket.agent;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Cross-process marker that keeps the guardian alive through a deliberate accessibility rebind. */
final class AccessibilityRepairState {
    private static final long WINDOW_MS=120_000L;
    private static File marker(Context context){return new File(context.getFilesDir(),"accessibility-repair.pending");}

    static void begin(Context context) {
        File target=marker(context),temp=new File(target.getParentFile(),target.getName()+".tmp");
        byte[] bytes=Long.toString(System.currentTimeMillis()+WINDOW_MS).getBytes(StandardCharsets.UTF_8);
        try(FileOutputStream output=new FileOutputStream(temp)) {
            output.write(bytes);output.getFD().sync();
        } catch(Exception failure) {throw new IllegalStateException("Could not persist accessibility repair marker",failure);}
        if(target.exists()&&!target.delete())throw new IllegalStateException("Could not replace accessibility repair marker");
        if(!temp.renameTo(target)){temp.delete();throw new IllegalStateException("Could not commit accessibility repair marker");}
    }

    static boolean pending(Context context) {
        File file=marker(context);
        if(!file.isFile())return false;
        try {
            long deadline=Long.parseLong(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).trim());
            if(deadline>=System.currentTimeMillis())return true;
        } catch(Exception ignored) {}
        file.delete();return false;
    }

    static void clear(Context context) {try {marker(context).delete();} catch(RuntimeException ignored) {}}
}

package dev.pocket.agent;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reserve and finish under the owner's monitor, but reconcile tasks outside it.
 * A slow removal cannot hold up an unrelated display's creation or interaction.
 */
final class DisplayRemovalSequence {
    private DisplayRemovalSequence() {}

    static <T> void run(
        Object lock,
        Supplier<T> reserve,
        Consumer<T> reconcile,
        Consumer<T> close,
        Consumer<T> finish,
        Runnable releaseReservation
    ) {
        final T selected;
        synchronized(lock) {
            selected=reserve.get();
        }
        try {
            reconcile.accept(selected);
            // Native VirtualDisplay.release / ImageReader.close may also block.
            close.accept(selected);
            synchronized(lock) {
                finish.accept(selected);
            }
        } finally {
            synchronized(lock) {
                releaseReservation.run();
            }
        }
    }
}

package dev.pocket.control;

import java.util.List;

/** Device contract, independent of MCP, desktop frameworks, and an agent/model. */
public interface ControlBackend {
    Observation observe(boolean includeImage, int maxImageDimension, int maxNodes) throws ControlException;
    Result execute(Action action) throws ControlException;

    record Node(int index, String resourceId, String text, String description, String role,
                int left, int top, int right, int bottom, boolean enabled, boolean focused,
                boolean editable, boolean clickable, boolean longClickable, boolean scrollable) {}

    record Observation(String snapshotId, long capturedAtMs, String packageName, int windowId,
                       int coordinateWidth, int coordinateHeight, boolean interactive, boolean locked,
                       List<Node> nodes, boolean nodesTruncated, byte[] image, String imageMime,
                       int imageWidth, int imageHeight) {
        public Observation { nodes = List.copyOf(nodes); image = image == null ? null : image.clone(); }
        @Override public byte[] image() { return image == null ? null : image.clone(); }
    }

    /** 'dispatched' confirms input acceptance; 'verified' requires a checked postcondition. */
    record Result(String status, String message) {}

    record Selector(String resourceId, String text, String description) {
        public boolean empty() { return resourceId == null && text == null && description == null; }
    }

    enum Kind { TAP, CLICK, LONG_CLICK, LONG_PRESS, SWIPE, SCROLL, TYPE, KEY }

    record Action(Kind kind, String snapshotId, Integer x, Integer y, Integer endX, Integer endY,
                  int durationMs, String text, boolean replace, String key, String direction,
                  Selector selector) {}
}

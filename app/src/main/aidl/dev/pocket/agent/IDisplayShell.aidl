package dev.pocket.agent;
interface IDisplayShell {
    int createDisplay(int width, int height, int dpi) = 1;
    void removeDisplay(int displayId) = 2;
    boolean launchActivity(int displayId, String componentName) = 3;
    boolean injectKey(int displayId, int keyCode) = 4;
    String health() = 5;
    void destroy() = 16777114;
}

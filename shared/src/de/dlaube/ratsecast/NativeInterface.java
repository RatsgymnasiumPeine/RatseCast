package de.dlaube.ratsecast;

public interface NativeInterface {
    public void keyDown(int keyCode, boolean keyDown);
    public void mouseMove(int x, int y);
    public void mouseButton(int buttonCode, boolean buttonDown, int x, int y);
    public void mouseWheel(boolean direction);

    public int[] getImageBuffer(int x, int y, int width, int height);

    public int getScreenWidth();
    public int getScreenHeight();
}

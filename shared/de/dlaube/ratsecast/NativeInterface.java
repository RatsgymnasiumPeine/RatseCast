package de.dlaube.ratsecast;

public interface NativeInterface {
    public void keyDown(int keyCode, boolean keyDown);
    public void mouseMove(int x, int y);
    public void mouseButton(int buttonCode, boolean buttonDown, int x, int y);
    public void mouseWheel(boolean direction);
}

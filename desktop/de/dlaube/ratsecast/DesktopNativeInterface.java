package de.dlaube.ratsecast;

import java.awt.*;

public class DesktopNativeInterface implements NativeInterface{

    private Robot robot;

    public DesktopNativeInterface(){
        robot = new Robot();
    }

    @Override
    public void keyDown(int keyCode, boolean keyDown) {
        if(keyDown)
            robot.keyPress(keyCode);
        else
            robot.keyRelease(keyCode);
    }

    @Override
    public void mouseMove(int x, int y) {
        robot.mouseMove(x, y);
    }

    @Override
    public void mouseButton(int buttonCode, boolean buttonDown, int x, int y) {
        robot.mouseMove(x, y);
        if(buttonDown)
            robot.mousePress(buttonCode);
        else
            robot.mouseRelease(buttonCode);
    }

    @Override
    public void mouseWheel(boolean direction) {
        robot.mouseWheel(direction ? 100 : -100);
    }
}

package de.dlaube.ratsecast;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

public class DesktopNativeInterface implements NativeInterface{

    private Robot robot;

    public DesktopNativeInterface(){
        try {
            robot = new Robot();
        } catch(Exception e){
            System.err.println("Error initializing Robot");
        }
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

    @Override
    public int[] getImageBuffer(int x, int y, int width, int height) {
        Rectangle screenRect = new Rectangle(x, y, width, height);
        BufferedImage colorImage = robot.createScreenCapture(screenRect);

        return  ((DataBufferInt) colorImage.getRaster().getDataBuffer()).getData();
    }

    @Override
    public int getScreenWidth() {
        return Toolkit.getDefaultToolkit().getScreenSize().width;
    }

    @Override
    public int getScreenHeight() {
        return Toolkit.getDefaultToolkit().getScreenSize().height;
    }
}

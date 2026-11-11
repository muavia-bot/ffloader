package com.venice.ffloader;
interface IEngine {
    boolean start(String pkg);
    byte[] poll(int sw, int sh);
    boolean touch(float x0, float y0, float x1, float y1, int steps, int ms);
    boolean tap(float x, float y);
    String info();
    void stop();
}

package com.nothing.thirdparty;

interface IGlyphService {
    int setFrameColors(in int[] colors);
    int openSession();
    int closeSession();
    void register(String key);
    int registerSDK(String packageName, String key);
    int registerMatrixSDK(String packageName, String key);
    int setMatrixColors(in int[] colors);
    int setGlyphMatrixTimeout(int timeout);
    int setAppMatrixColors(in int[] colors);
    int closeAppMatrix();
}

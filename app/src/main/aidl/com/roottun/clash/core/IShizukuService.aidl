package com.roottun.clash.core;

import android.os.ParcelFileDescriptor;

interface IShizukuService {
    String exec(String command);
    List<String> listPackages(int userId);
    List<String> listSystemPackages(int userId);
    String probeTun();
    String createTun();
    ParcelFileDescriptor getTunFd();
    String destroyTun();
}

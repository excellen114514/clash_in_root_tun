package com.roottun.clash.core;

interface IShizukuService {
    String exec(String command);
    List<String> listPackages(int userId);
    List<String> listSystemPackages(int userId);
}

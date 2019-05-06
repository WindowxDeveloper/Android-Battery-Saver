package com.example.raphael.tcc.BackgroundServices;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.example.raphael.tcc.AppUI.BubbleButton;
import com.example.raphael.tcc.AppUI.SpeedUpNotification;
import com.example.raphael.tcc.DataBase.AppDbHelper;
import com.example.raphael.tcc.Logv;
import com.example.raphael.tcc.Managers.AppManager;
import com.example.raphael.tcc.Managers.BrightnessManager;
import com.example.raphael.tcc.Managers.CpuManager;
import com.example.raphael.tcc.SingletonClasses;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static java.util.concurrent.TimeUnit.SECONDS;

public class BackgroundService extends Service {
    /**
     * Objects
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final BubbleButton bubbleButton = new BubbleButton();
    private final SpeedUpNotification speedUpNotification = new SpeedUpNotification(); //Create notification handler
    private AppManager appManager = new AppManager();
    private BrightnessManager brightnessManager = new BrightnessManager();
    private CpuManager cpuManager = SingletonClasses.getInstance();
    private AppDbHelper appDbHelper = AppDbHelper.getInstance(this);
    private int timer = 0;
    /**
     * Variables
     */
    private ArrayList<String> arrayList = new ArrayList<>();
    private static boolean loaded = true, changeDetector = false, firstTimeOnSystem = false, screenOnOff = true, loadLastAppOnScreenOnOff = false;
    private static String lastApp = "";
    private static int brightnessValue;
    private boolean notifEnabled;
    private boolean buttonEnabled;
    private String TAG = this.getClass().getName();
    //New Variable
    private static boolean screenOn = true, appChanged = false, speedSet = false, isExcludedApp;
    private String currentApp;
    private double threshold = 1;
    ArrayList<String> appData;
    private final static HashMap<String, Boolean> excludedApps = initExcludedAppsList();


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static List<Integer> currentSpeeds;
    private static List<Integer> currentThresholds;

    private void log(String msg) {
        Logv.log(getClass().getSimpleName() + " - " + msg);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                //try {
                log("runnable started");
                Log.i(TAG, "The Thread start to Running.");
                //If screenOnOff is off, the thread will be return;
                if (!screenOnOff) {
                    log("runnable - screenOnOff - then return");

                    return;
                }
                //If screen is on
                if (screenOn) {
                    log("runnable - screen is on");

                    //Get the App Current Running
                    currentApp = appManager.getAppRunningOnForeground(BackgroundService.this);
                    log("currentApp: " + currentApp + " - lastApp" + lastApp);
                    log("appData: " + appData);
                    log("isExcludedApp: " + isExcludedApp);

                    // If the app changed, we will save the current settings and check new app
                    if (!currentApp.equals(lastApp)) {
                        log("runnable - foreground app changed");

                        //save last app setting in the database if lastApp exists and lastApp is not excluded app to avoid overhead
                        if (!lastApp.isEmpty() && !isExcludedApp) {
                            appDbHelper.updateAppConfiguration(lastApp, brightnessManager.getScreenBrightnessLevel(), cpuManager.getArrayListCoresSpeed(), currentThresholds);
                        }

                        //foreground app changed then check if it's an excluded app
                        isExcludedApp = isExcludedApp(currentApp);

                        if (!isExcludedApp) {
                            //get appData from database only if the new app is not excluded app to avoid overhead
                            appData = appDbHelper.getAppData(CpuManager.getNumberOfCores(), currentApp);
                            speedSet = false;
                        }

                        lastApp = currentApp;
                    }
                    // Update the last app to current app.
                    else {
                        log("runnable - currentApp still running - isExcludedApp: " + isExcludedApp);
                        if (!isExcludedApp) {

                            //Retrieve app form data base
                            //If the current app did not exist in the data base
                            if (appData == null || appData.size() == 0) {
                                log("Init the new App");
                                Log.i(TAG, "The current app " + currentApp + "does not exist in the database");
                                Log.i(TAG, "Init the new App");
                                //Set the CPU to the highest frequency.
                                cpuManager.adjustConfiguration(appData);
                                currentSpeeds = cpuManager.getArrayListCoresSpeed();
                                currentThresholds = new ArrayList<>(CpuManager.getNumberOfCores());
                                appDbHelper.insertAppConfiguration(currentApp, brightnessManager.getScreenBrightnessLevel(), cpuManager.getArrayListCoresSpeed(), currentThresholds);
                            } else {
                                log("The current app " + currentApp + " exist in the database - currentSpeed:" + cpuManager.getArrayListCoresSpeed());
                                Log.i(TAG, "The current app " + currentApp + " exist in the database");
                                Log.i(TAG, "Setting the configuration based on the database");
                                //If there is a configuration from database, set the system based on the configuration
                                if (!speedSet) {
                                    brightnessValue = Integer.parseInt(appData.get(1));
                                    cpuManager.adjustConfiguration(appData);
                                    currentSpeeds = cpuManager.getArrayListCoresSpeed();
                                }
                                currentThresholds = getIntegerArray(appData.subList(2 + CpuManager.getNumberOfCores(), appData.size()));
                                //Frequency based on the previous saved data
                            }
                            //If we already have the threshold
                            if (currentThresholds.size() > 0) {
                                for (int i = currentSpeeds.size() - 1; i >= 0; i--) {
                                    if (currentSpeeds.get(i) > 0) {
                                        if (currentSpeeds.get(i) > currentThresholds.get(i))
                                            if ((currentSpeeds.get(i) - ((currentSpeeds.get(i) - currentThresholds.get(i)) * threshold)) > currentThresholds.get(i)) {
                                                currentSpeeds.set(i, (int) (currentSpeeds.get(i) - ((currentSpeeds.get(i) - currentThresholds.get(i)) * (1 - threshold))));
                                                currentSpeeds = cpuManager.setSpeedByArrayListDESC(currentSpeeds);
                                                speedSet = true;
                                                break;
                                            }
                                        Log.d(this.getClass().getName(), "current speed: " + i + ", " + currentSpeeds.get(i));
                                    }
                                }

                            } else {
                                // If this a new app, decrease the speed by the plan.
                                for (int i = currentSpeeds.size() - 1; i >= 0; i--) {
                                    if (currentSpeeds.get(i) > 0) {
                                        currentSpeeds.set(i, (int) (currentSpeeds.get(i) * threshold));
                                        Log.d(this.getClass().getName(), "current speed: " + i + ", " + currentSpeeds.get(i));
                                        break;
                                    }
                                }
                                currentSpeeds = cpuManager.setSpeedByArrayListDESC(currentSpeeds);
                                speedSet = true;
                            }
                        }
                    }
                }
                //Once the screen is off, save the last status and set it to the lowest frequency
                else {
                    log("runnable - screen is off");


                    //saving setting only if currentApp is not an excluded app
                    if (!isExcludedApp) {
                        // If we have thresholds, we save the threshold, otherwise, we save the current settings.
                        if (currentThresholds == null || currentThresholds.size() == 0) {
                            appDbHelper.insertAppConfiguration(currentApp, brightnessManager.getScreenBrightnessLevel(), cpuManager.getArrayListCoresSpeed(), new ArrayList<Integer>(CpuManager.getNumberOfCores()));
                        } else {
                            //check if configuration changed, we have to save configuration only once to prevent saving the same settings multiple times to avoid overhead
                            if (isConfigurationChanged()) {
                                appDbHelper.updateAppConfiguration(currentApp, brightnessManager.getScreenBrightnessLevel(), cpuManager.getArrayListCoresSpeed(), currentThresholds);
                            }
                        }
                    }


                    //Once we saved the threshold and current speed and threshold, we set screenOn to true to avoid save it again.
                    //Also, we will use another parameter to detect weather screen is on or not.
                    screenOn = true;
                    screenOnOff = false;
                    speedSet = false;
                    log("runnable - sleep mode is on");

                    Log.i(TAG, "Enter Sleep Mode, Set the CPU to the min speed. Turn off all the core except core 0 ");
                    cpuManager.setToMinSpeed();
                }
                Log.i(TAG, "The Thread Finished Running.");
                /*} catch (Exception e) {
                    Log.e(TAG, e.getMessage());
                }*/
            }
        };
        scheduler.scheduleAtFixedRate(runnable, 1, 2, SECONDS);
        return START_NOT_STICKY;
    }

    private boolean isExcludedApp(String targetApp) {
        log("isExcludedApp() - result: " + excludedApps.get(targetApp));
        return excludedApps.get(targetApp) != null;
    }

    private static HashMap<String, Boolean> initExcludedAppsList() {

        HashMap<String, Boolean> list = new HashMap<>();
        list.put("com.android.launcher", true);
        list.put("com.google.android.googlequicksearchbox", true);
        list.put("com.example.raphael.tcc", true);
        list.put("com.android.systemui", true);
        list.put("android", true);

        return list;
    }

    private boolean isConfigurationChanged() {

        //we have to compare current brightness and speeds with the appData's brightness and speeds
        //to detect if there change happen
        if (appData != null) {
            //if brightness is changed then return true
            if (!appData.get(1).equals(brightnessManager.getScreenBrightnessLevel())) {
                return true;
            }
            List<Integer> coresSpeeds = cpuManager.getArrayListCoresSpeed();
            for (int i = 0; i < CpuManager.getNumberOfCores(); i++) {

                //we have to add 2 to i to avoid to the first two items in appData
                if (!appData.get(i + 2).equals(coresSpeeds.get(i))) {
                    return true;
                }
            }
        }

        return false;
    }

    private List<Integer> getIntegerArray(List<String> stringArray) {
        List<Integer> result = new ArrayList<Integer>();
        for (String stringValue : stringArray) {
            try {
                //Convert String to Integer, and store it into integer array list.
                result.add(Integer.parseInt(stringValue));
            } catch (NumberFormatException nfe) {
                //System.out.println("Could not parse " + nfe);
                result.add(0);
            }
        }
        return result;
    }

    /*@Override
    //TODO I think the algorithm is not saving correctly the changes of the user when the screen turns off
    //Todo the cause may be that flag screenOnOff doesnt have a if case when it is False
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                Log.i(this.getClass().getName(), "Running the thread");
                if (screenOnOff) {
                    loadLastAppOnScreenOnOff = true;//Reload last app
                    actualApp = appManager.getAppRunningOnForeground(BackgroundService.this);
                    if (actualApp.equals("com.android.launcher") || actualApp.equals("com.google.android.googlequicksearchbox"))
                        timer++;
                    if (timer >= 5) {
                        arrayList.clear();
                        setAppConfiguration(arrayList);
                        timer = 0;
                        lastApp = "";
                    }
                    if (!actualApp.equals("com.android.launcher") && !actualApp.equals("com.google.android.googlequicksearchbox") && !actualApp.equals(lastApp) && !actualApp.equals("com.example.raphael.tcc")
                            && !actualApp.equals("com.android.systemui") && !actualApp.equals("android") && !actualApp.isEmpty())
                        loaded = false;
                    if (!loaded) {//Retrieve app info from DB
                        //reload actualApp
                        arrayList = appDbHelper.getAppData(CpuManager.getNumberOfCores(), actualApp);
                        //If got the app from the database
                        if (!arrayList.isEmpty()) {
                            brightnessValue = Integer.parseInt(arrayList.get(1));
                            cpuManager.adjustConfiguration(arrayList);
                            //otherwise:
                        } else {
                            cpuManager.adjustConfiguration(new ArrayList<String>());
                        }
                        if (!actualApp.equals(lastApp) && !lastApp.equals("")) {
                            if (changeDetector) {
                                appDbHelper.updateAppConfiguration(lastApp, brightnessManager.getScreenBrightnessLevel(), cpuManager.getArrayListCoresSpeed());
                                currentSpeeds = new ArrayList<>();
                            } else if (firstTimeOnSystem) {
                                appDbHelper.insertAppConfiguration(lastApp, brightnessManager.getScreenBrightnessLevel(), cpuManager.getArrayListCoresSpeed());
                                currentSpeeds = new ArrayList<>();
                            }
                        }
                        //setAppConfiguration(arrayList);
                        loaded = false;  // WHY LOAD CHANGED HERE
                        lastApp = actualApp;
                        changeDetector = false;
                    } else {
                        if (currentSpeeds != null && currentSpeeds.size() > 0) {
                            for (int i = currentSpeeds.size() - 1; i >= 0; i--) {
                                if (currentSpeeds.get(i) > 0) {
                                    currentSpeeds.set(i, (int) (currentSpeeds.get(i) * 0.75));
                                    Log.e(this.getClass().getName(), "current speed: " + i + ", " + currentSpeeds.get(i));
                                    break;
                                }
                            }
                            cpuManager.setSpeedByArrayListDESC(currentSpeeds);
                        } else {
                            cpuManager.adjustConfiguration(new ArrayList<String>());
                            currentSpeeds = cpuManager.getArrayListCoresSpeed();
                        }
                    }
                } else if (loadLastAppOnScreenOnOff) {//When the screen turn off, put all config to min.
                    loadLastAppOnScreenOnOff = false;
                    if (arrayList.isEmpty())
                        appDbHelper.updateAppConfiguration(actualApp, BrightnessManager.minLevel, cpuManager.getArrayListCoresSpeed());
                    else if (changeDetector || firstTimeOnSystem)
                        appDbHelper.updateAppConfiguration(actualApp, brightnessValue, cpuManager.getArrayListCoresSpeed());
                    loaded = false;//reload config
                    arrayList.clear();
                    cpuManager.adjustConfiguration(arrayList);
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                Log.i(this.getClass().getName(), "run: Finished Running");
            }

        }, 1, 3, SECONDS);
        return START_NOT_STICKY;
    }*/

    //Once receive the request
    public void levelUp() {
        cpuManager.setSpeedByArrayListASC(currentSpeeds);
        currentThresholds = currentSpeeds;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction("com.example.raphael.tcc.REQUESTED_MORE_CPU");
        registerReceiver(broadcastRcv, filter);
        SharedPreferences sharedPreferences = getApplicationContext().getSharedPreferences("shared_settings", Context.MODE_PRIVATE);
        buttonEnabled = sharedPreferences.getBoolean("bubble_button", false);
        notifEnabled = sharedPreferences.getBoolean("notification", false);

        if (buttonEnabled)
            bubbleButton.createFeedBackButton(getApplicationContext());
        if (notifEnabled)
            speedUpNotification.createSpeedUpNotification(getApplicationContext());
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        Toast.makeText(this, "Service stopped", Toast.LENGTH_LONG).show();
        unregisterReceiver(broadcastRcv);
        scheduler.shutdown();
        cpuManager.giveAndroidFullControl();

        speedUpNotification.removeNotification(this);
        bubbleButton.removeView();
        stopService(new Intent(this, BackgroundService.class));
    }

    private void setAppConfiguration(ArrayList<String> appConfiguration) {
        //Empty ArrayList? No records found -> set to minimum
        cpuManager.adjustConfiguration(appConfiguration);
        if (appConfiguration.size() != 0) {
            brightnessManager.setBrightnessLevel(Integer.parseInt(appConfiguration.get(1)));
            firstTimeOnSystem = false;
        } else {
            brightnessManager.setBrightnessLevel(BrightnessManager.minLevel);
            firstTimeOnSystem = true;
        }
    }

    private final BroadcastReceiver broadcastRcv = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            log("BroadcastReceiver - onReceive() - action:" + action);

            int value;
            if (action.equals("com.example.raphael.tcc.REQUESTED_MORE_CPU")) {
                value = intent.getIntExtra("valorCpuUsuario", 0);
                // update thresho
                // ld
                // example if user selected 50% then it will be 0.5
                threshold = value / 100;
                Log.i(TAG, "Received the REQUEST_MORE_CPU action");
                levelUp();
                changeDetector = true;
            }
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                //changeDetector = false;
                screenOn = false;
            }
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                screenOnOff = true;
                screenOn = true;
            }

            if (intent.getAction().equals("com.example.raphael.tcc.ENABLE_BUTTON"))
                bubbleButton.createFeedBackButton(getApplicationContext());

            if (intent.getAction().equals("com.example.raphael.tcc.DISABLE_BUTTON"))
                bubbleButton.removeView();

            if (intent.getAction().equals("com.example.raphael.tcc.ENABLE_NOTIFICATION"))
                speedUpNotification.createSpeedUpNotification(getApplicationContext());

            if (intent.getAction().equals("com.example.raphael.tcc.DISABLE_NOTIFICATION"))
                speedUpNotification.removeNotification(getApplicationContext());
        }
    };
}


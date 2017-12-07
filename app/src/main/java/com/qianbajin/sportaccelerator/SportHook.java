package com.qianbajin.sportaccelerator;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.os.Process;
import android.text.TextUtils;

import com.alibaba.fastjson.JSON;
import com.qianbajin.sportaccelerator.bean.AliStepRecord;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
/**
 * @author Administrator
 * @Created at 2017/11/5 0005  22:11
 * @des
 */

public class SportHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    public static final String DYLAN = "cn.bluemobi.dylan.step";
    public static final String QQ = "com.tencent.mobileqq";
    public static final String QQ_MSF = "com.tencent.mobileqq:MSF";
    public static final String ALIPAY = "com.eg.android.AlipayGphone";
    public static final String ALI_EXT = "com.eg.android.AlipayGphone:ext";
    public static final String SAMSUNG_HEALTH = "com.sec.android.app.shealth";
    private static int MAX_QQ = 60000;
    private static int RATE_QQ = 15, RATE_ALI = 15, M_SHEALTH = 15, M_DYLAN = 15, SENSOR_STEP, ALI_TODAY_STEP;
    private static float sStep, mPreStep;
    private static String sPackageName, sProcessName;
    private static XSharedPreferences sXsp;
    private static boolean sLog = true;
    private static Object sObject;
    private static SensorHook sSensorHook;
    private static int sAliUpperLimit;
    private static Calendar sCalendar;

    static {
        XposedBridge.log("SportHook  static myPid:" + Process.myPid());
    }

    private static void printString(String... msg) {
        if (!sLog) {
            return;
        }
        StringBuilder sb = new StringBuilder(32);
        for (String s : msg) {
            sb.append(s);
        }
        XposedBridge.log(sb.toString());
    }

    private static void printLog(Object... msg) {
        if (!sLog) {
            return;
        }
        StringBuilder sb = new StringBuilder(64);
        for (Object o : msg) {
            sb.append(o.toString());
        }
        XposedBridge.log(sb.toString());
    }

    private static void loadConfig() {
        sXsp.reload();
        Map<String, ?> all = sXsp.getAll();
        if (all.isEmpty()) {
            XposedBridge.log(BuildConfig.APPLICATION_ID + "加载资源失败");
        }
        printLog(sXsp.getAll());
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        sPackageName = lpparam.packageName;
        sProcessName = lpparam.processName;
        printLog("加载:", sPackageName, "     进程:", lpparam.processName, "     pid:", Process.myPid());

        if (sPackageName.equals(Constant.PK_ALIPAY)) {
            if (sProcessName.equals(Constant.PK_ALIPAY)) {
                loadConfig();
                sAliUpperLimit = Integer.parseInt(sXsp.getString(Constant.SP_KEY_ALI_UPPER_LIMIT, "30000"));
                Class<?> application = XposedHelpers.findClass("android.app.Application", lpparam.classLoader);
                Method onCreate = XposedHelpers.findMethodExact(application, "onCreate", new Class[0]);
                XposedBridge.hookMethod(onCreate, new AliApplicationHook(lpparam));
                RATE_ALI = Integer.parseInt(sXsp.getString(Constant.SP_KEY_ALI_RATE, "15"));
            }
            if (sProcessName.equals(ALI_EXT)) {
                boolean speedUp = sXsp.getBoolean(Constant.PK_ALIPAY, false);
                if (speedUp) {
                    direHook(lpparam, RATE_ALI);
                }
            }
        }

        if (sProcessName.equals(QQ_MSF)) {
            direHook(lpparam, RATE_QQ);
        }
    }

    private static void direHook(XC_LoadPackage.LoadPackageParam lpparam, int rate) {
        Class<?> aClass = XposedHelpers.findClass("android.hardware.SystemSensorManager$SensorEventQueue", lpparam != null ? lpparam.classLoader : ClassLoader.getSystemClassLoader());
        Method dispatchSensorEvent = XposedHelpers.findMethodExact(aClass, "dispatchSensorEvent", int.class, float[].class, int.class, long.class);
        DireSportHook sportHook = new DireSportHook(rate);
        printLog("sportHook:", sportHook);
        XposedBridge.hookMethod(dispatchSensorEvent, sportHook);
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        XposedBridge.log("initZygote初始化加载" + Process.myPid());
        sXsp = new XSharedPreferences(BuildConfig.APPLICATION_ID);
        sCalendar = Calendar.getInstance();
    }

    private static class AliApplicationHook extends XC_MethodHook {

        private final XC_LoadPackage.LoadPackageParam mLpparam;

        public AliApplicationHook(XC_LoadPackage.LoadPackageParam lpparam) {
            mLpparam = lpparam;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            Application application = (Application) param.thisObject;
            printLog("onCreate", "application:", application);
            boolean edit = sXsp.getBoolean(Constant.SP_KEY_ALI_STEP_EDIT, false);
            SharedPreferences baseSp = application.getSharedPreferences("NewPedoMeter", Context.MODE_PRIVATE);
            String step = baseSp.getString(Constant.ALI_SP_KEY_BASESTEP, "0");
            AliStepRecord baseSteps = JSON.parseObject(step, AliStepRecord.class);
            boolean today = getTodayStep(baseSp);
            direHook(mLpparam, RATE_ALI);
            if (edit) {
                step = sXsp.getString(Constant.SP_KEY_ALI_EXPECT_STEP, "8000");
                int expectStep = Integer.parseInt(step);
                if (ALI_TODAY_STEP < expectStep) {
                    if (today) {
                        baseSteps.setSteps(expectStep);
                    } else {
                        long mills = sCalendar.getTimeInMillis() + 10;
                        baseSteps.setTime(mills);
                    }
                    String json = JSON.toJSON(new AliStepRecord[]{baseSteps}).toString();
                }

            }
            SharedPreferences recordSp = application.getSharedPreferences("NewPedoMeter_private", Context.MODE_PRIVATE);
            if (recordSp != null) {
                String stepRecords = recordSp.getString(Constant.ALI_SP_KEY_STEPRECORD, "");
                List<AliStepRecord> stepRecordList = JSON.parseArray(stepRecords, AliStepRecord.class);
                int sensorStep = 0;
                if (stepRecordList != null && !stepRecordList.isEmpty()) {
                    AliStepRecord stepRecord = stepRecordList.get(stepRecordList.size() - 1);
                    sensorStep = stepRecord.getSteps();
                }
                SENSOR_STEP = sensorStep;
                printLog("baseStep:", ALI_TODAY_STEP, "    sensorStep:", sensorStep, "    SENSOR_STEP:", SENSOR_STEP);
            }
        }

        private boolean getTodayStep(SharedPreferences baseSp) {
            if (baseSp == null) {
                return false;
            }
            String step = baseSp.getString(Constant.ALI_SP_KEY_BASESTEP, "");
            if (!TextUtils.isEmpty(step)) {
                AliStepRecord baseSteps = JSON.parseObject(step, AliStepRecord.class);
                long time = baseSteps.getTime();
                long timeInMillis = getToday0();
                boolean today = time > timeInMillis;
                ALI_TODAY_STEP = today ? baseSteps.getSteps() : 0;
            }
            return false;
        }

        private long getToday0() {
            sCalendar.setTimeInMillis(System.currentTimeMillis());
            sCalendar.set(Calendar.HOUR, 0);
            sCalendar.set(Calendar.MINUTE, 0);
            sCalendar.set(Calendar.SECOND, 0);
            sCalendar.set(Calendar.MILLISECOND, 0);
            return sCalendar.getTimeInMillis();
        }

    }


    private static class DireSportHook extends XC_MethodHook {

        private final int mRate;
        private int mCount;

        public DireSportHook(int rate) {
            mRate = rate;
            printLog("DireSportHook rate:", rate, "   ALI_TODAY_STEP:", ALI_TODAY_STEP);
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            float value = ((float[]) param.args[1])[0];
            ((float[]) param.args[1])[0] = value + mRate * mCount;
            mCount++;
            printLog(sProcessName, "  传感器值:", value, "   ", RATE_ALI + " * " + mCount, "   步数:", ((float[]) param.args[1])[0]);
        }
    }


    private static class SensorHook extends XC_MethodHook {

        public static int COUNT;
        private HashMap<Integer, Sensor> mHandleToSensor;

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            sObject = param.thisObject;
            int handle = (int) param.args[0];
            if (mHandleToSensor == null) {
                Class<?> SystemSensorManager = XposedHelpers.findClass("android.hardware.SystemSensorManager", XposedBridge.BOOTCLASSLOADER);
                Field handleToSensor = XposedHelpers.findField(SystemSensorManager, "mHandleToSensor");
                ;
//                    handleToSensor = XposedHelpers.findField(SystemSensorManager, "sHandleToSensor");
                Field mManager = XposedHelpers.findField(param.thisObject.getClass().getSuperclass(), "mManager");
                Object o = mManager.get(param.thisObject);
                mHandleToSensor = (HashMap) handleToSensor.get(o);
            }
            Sensor sensor = mHandleToSensor.get(handle);
            if (sensor == null) {
                printLog("sensor == null");
                mHandleToSensor = null;
                // sensor disconnected
                return;
            }
            int type = sensor.getType();
            if (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR) {
                float value = ((float[]) param.args[1])[0];
                if (sPackageName.equals(ALIPAY)) {
                    int max = Math.max(((int) value), SENSOR_STEP);
                    ((float[]) param.args[1])[0] += RATE_ALI * COUNT;
                    COUNT++;
                    printLog(sProcessName, "  传感器值:", value, "   ", RATE_ALI + " * " + COUNT, "   步数:", ((float[]) param.args[1])[0]);
                }
                if (sPackageName.equals(QQ)) {
                    ((float[]) param.args[1])[0] += RATE_QQ * COUNT;
                    COUNT++;
                    printLog(sProcessName, "  传感器值:", value, "   ", RATE_QQ + " * " + COUNT, "   步数:", ((float[]) param.args[1])[0]);
                }
                if (sPackageName.equals(DYLAN)) {
                    ((float[]) param.args[1])[0] += M_DYLAN * COUNT;
                    sStep = value;
                    COUNT++;
                    printLog(sProcessName, "  传感器值:", value, "   ", M_DYLAN + " * " + COUNT, "   步数:", ((float[]) param.args[1])[0]);
                }
                if (sPackageName.equals(SAMSUNG_HEALTH)) {
                    ((float[]) param.args[1])[0] += M_SHEALTH * COUNT;
                    COUNT++;
                    printLog(sProcessName, "  传感器值:", value, "   ", M_SHEALTH + " * " + COUNT, "   步数:", ((float[]) param.args[1])[0]);
                }
            }

        }

    }

}

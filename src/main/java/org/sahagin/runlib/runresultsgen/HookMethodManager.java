package org.sahagin.runlib.runresultsgen;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

import org.apache.commons.io.Charsets;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.openqa.selenium.io.IOUtils;
import org.sahagin.runlib.external.CaptureStyle;
import org.sahagin.runlib.external.adapter.AdapterContainer;
import org.sahagin.runlib.runresultsgen.StackLineUtils.LineReplacer;
import org.sahagin.share.CommonPath;
import org.sahagin.share.CommonUtils;
import org.sahagin.share.Config;
import org.sahagin.share.IllegalDataStructureException;
import org.sahagin.share.Logging;
import org.sahagin.share.runresults.LineScreenCapture;
import org.sahagin.share.runresults.RootMethodRunResult;
import org.sahagin.share.runresults.RunFailure;
import org.sahagin.share.runresults.StackLine;
import org.sahagin.share.srctree.SrcTree;
import org.sahagin.share.srctree.TestMethod;
import org.sahagin.share.srctree.code.CodeLine;
import org.sahagin.share.srctree.code.Field;
import org.sahagin.share.srctree.code.SubMethodInvoke;
import org.sahagin.share.srctree.code.TestStep;
import org.sahagin.share.srctree.code.TestStepLabel;
import org.sahagin.share.srctree.code.VarAssign;
import org.sahagin.share.yaml.YamlUtils;

public class HookMethodManager {
    private static Logger logger = Logging.getLogger(HookMethodManager.class.getName());
    private SrcTree srcTree;
    private File runResultsRootDir;
    private File captureRootDir;
    private int currentCaptureNo = 1;
    private RootMethodRunResult currentRunResult = null;
    private String currentActualRootMethodSimpleName = null;
    private String methodKeyCache = null; // used in getTestMethod method
    private TestMethod methodCache = null; // used in getTestMethod method
    private long startMethodTime;
    private long startTestStepLabelTime;
    private LinkedHashMap<String, Long> startTimeMap = new LinkedHashMap<>();

    public HookMethodManager(SrcTree srcTree, Config config) {
        if (srcTree == null) {
            throw new NullPointerException();
        }
        if (config == null) {
            throw new NullPointerException();
        }
        this.srcTree = srcTree;
        runResultsRootDir = CommonPath.runResultRootDir(config.getRootBaseReportIntermediateDataDir());
        captureRootDir = CommonPath.inputCaptureRootDir(config.getRootBaseReportIntermediateDataDir());
    }

    // initialize runResult information if the method for the arguments is root method
    public void beforeMethodHook(String hookedClassQualifiedName,
            String hookedMethodSimpleName, String actualHookedMethodSimpleName) {
        if (currentRunResult != null) {
            return; // maybe called inside of the root method
        }

        List<TestMethod> rootMethods = srcTree.getRootMethodTable().getByName(
                hookedClassQualifiedName, hookedMethodSimpleName);
        if (rootMethods.size() == 0) {
            return; // hooked method is not root method
        }
        assert rootMethods.size() == 1;
        TestMethod rootMethod = rootMethods.get(0);

        logger.info("beforeMethodHook: " + hookedMethodSimpleName);

        // initialize current captureNo and runResult
        currentCaptureNo = 1;
        currentRunResult = new RootMethodRunResult();
        currentRunResult.setRootMethodKey(rootMethod.getKey());
        currentRunResult.setRootMethod(rootMethod);
        currentActualRootMethodSimpleName = actualHookedMethodSimpleName;

        startMethodTime = System.currentTimeMillis();
    }

    // set up runFailure information
    // This method must be called before afterMethodHook is called
    public void methodErrorHook(String hookedClassQualifiedName,
            String hookedMethodSimpleName, Throwable e) {
        if (currentRunResult == null) {
            return; // maybe called outside of the root method
        }
        TestMethod rootMethod = currentRunResult.getRootMethod();
        if (!rootMethod.getTestClassKey().equals(hookedClassQualifiedName)
                || !rootMethod.getSimpleName().equals(hookedMethodSimpleName)) {
            return; // hooked method is not current root method
        }

        RunFailure runFailure = new RunFailure();
        runFailure.setMessage(e.getClass().getCanonicalName() + ": " + e.getLocalizedMessage());
        runFailure.setStackTrace(ExceptionUtils.getStackTrace(e));
        LineReplacer replacer = new LineReplacer() {

            @Override
            public void replace(String classQualifiedName, String methodSimpleName, int line) {
                super.replace(classQualifiedName, methodSimpleName, line);
                if (StringUtils.equals(methodSimpleName, currentActualRootMethodSimpleName)) {
                    replaceMethodSimpleName(currentRunResult.getRootMethod().getSimpleName());
                }
            }
        };
        List<StackLine> stackLines = StackLineUtils.getStackLines(srcTree, e.getStackTrace(), replacer);
        for (StackLine stackLine : stackLines) {
            runFailure.addStackLine(stackLine);
        }
        currentRunResult.addRunFailure(runFailure);

        captureScreenForStackLines(rootMethod, Arrays.asList(stackLines), Arrays.asList(-1));
    }

    // write runResult to YAML file if the method for the arguments is root method
    public void afterMethodHook(String hookedClassQualifiedName, String hookedMethodSimpleName) {
        if (currentRunResult == null) {
            return; // maybe called outside of the root method
        }
        TestMethod rootMethod = currentRunResult.getRootMethod();
        if (!rootMethod.getTestClassKey().equals(hookedClassQualifiedName)
                || !rootMethod.getSimpleName().equals(hookedMethodSimpleName)) {
            return; // hooked method is not current root method
        }

        long currentTime = System.currentTimeMillis();
        currentRunResult.setExecutionTime((int) (currentTime - startMethodTime));
        logger.info("afterMethodHook: " + hookedMethodSimpleName);

        // use encoded name to avoid various possible file name encoding problem
        // and to escape invalid file name character (Method name may contain such characters
        // if method is Groovy method, for example).
        File runResultFile = new File(String.format("%s/%s/%s", runResultsRootDir,
                CommonUtils.encodeToSafeAsciiFileNameString(hookedClassQualifiedName, Charsets.UTF_8),
                CommonUtils.encodeToSafeAsciiFileNameString(hookedMethodSimpleName, Charsets.UTF_8)));
        if (runResultFile.getParentFile() != null) {
            runResultFile.getParentFile().mkdirs();
        }
        // write runResult to YAML file
        YamlUtils.dump(currentRunResult.toYamlObject(), runResultFile);

        // clear current captureNo and runResult
        currentCaptureNo = -1;
        currentRunResult = null;
        currentActualRootMethodSimpleName = null;
    }

    // If the code line for the specified method and codeLineIndex is the last line
    // of a TestStepLabel block,
    // this method returns the code body index for the TestStepLabel, otherwise returns -1.
    private int getTestStepLabelIndexIfThisLineIsStepLastCode(
            TestMethod method, int codeLineIndex) {
        if (method.getCodeBody().size() -1 > codeLineIndex
                && !(method.getCodeBody().get(codeLineIndex + 1).getCode() instanceof TestStepLabel)) {
            // next code line is not TestStepLabel
            return -1;
        }

        // Next line is the next TestStepLabel, or this line is method last line,
        // so searches the TestSTepLabel for this statement
        int index = codeLineIndex;
        while (index >= 0) {
            if (method.getCodeBody().get(index).getCode() instanceof TestStepLabel) {
                return index;
            }
            index--;
        }
        return -1; // not TestStepLabel is found before the specified codeLineIndex line
    }

    // get TestMethod for the method information.
    // this method uses cache to improve performance
    private TestMethod getTestMethod(String classQualifiedName,
            String methodSimpleName, String argClassesStr) {
        String methodKey = TestMethod.generateMethodKey(
                classQualifiedName, methodSimpleName, argClassesStr);
        TestMethod result;
        if (StringUtils.equals(methodKeyCache, methodKey)) {
            result = methodCache; // re-use cache
        } else {
            try {
                result = srcTree.getTestMethodByKey(methodKey, true);
            } catch (IllegalDataStructureException e) {
                throw new RuntimeException(e);
            }
        }

        // cache key and method to improve method search performance.
        // caching is executed even if TestMethod is not found
        methodKeyCache = methodKey;
        methodCache = result;
        return result;
    }

    private List<Integer> getHookedCodeLineIndex(TestMethod method, int hookedLine) {
        List<Integer> result = new ArrayList<Integer>(2);
        for (int i = 0; i < method.getCodeBody().size(); i++) {
            CodeLine codeLine = method.getCodeBody().get(i);
            if (codeLine.getStartLine() <= hookedLine && hookedLine <= codeLine.getEndLine()) {
                result.add(i);
            }
        }
        return result;
    }

    // get the stackLines for the hook method code line.
    private List<StackLine> getCodeLineHookedStackLines(
            final String hookedMethodSimpleName, final String actualHookedMethodSimpleName,
            final int hookedLine, final int actualHookedLine) {
        // this replacer replaces method name and line to the actual hooked method name and line
        LineReplacer replacer = new LineReplacer() {

            @Override
            public void replace(String classQualifiedName, String methodSimpleName, int line) {
                super.replace(classQualifiedName, methodSimpleName, line);
                // replacing root method name
                if (StringUtils.equals(methodSimpleName, currentActualRootMethodSimpleName)) {
                    replaceMethodSimpleName(currentRunResult.getRootMethod().getSimpleName());
                }
                // replacing hooked method name and line number
                if (StringUtils.equals(methodSimpleName, actualHookedMethodSimpleName)
                        && (line == actualHookedLine)) {
                    replaceMethodSimpleName(hookedMethodSimpleName);
                    replaceLine(hookedLine);
                }
            }
        };

        List<StackLine> result = StackLineUtils.getStackLines(
                srcTree, Thread.currentThread().getStackTrace(), replacer);
        assert result.size() > 0;
        return result;
    }

    public void beforeCodeLineHook(String hookedClassQualifiedName,
            final String hookedMethodSimpleName, final String actualHookedMethodSimpleName,
            String hookedArgClassesStr, final int hookedLine, final int actualHookedLine) {
        if (currentRunResult == null) {
            return; // maybe called outside of the root method
        }

        TestMethod hookedTestMethod = getTestMethod(
                hookedClassQualifiedName, hookedMethodSimpleName, hookedArgClassesStr);
        if (hookedTestMethod == null) {
            return; // hooked method is not current root method
        }

        logger.info(String.format("beforeCodeLineHook: start: %s: %d(%d)",
                hookedMethodSimpleName, hookedLine, actualHookedLine));

        // don't use getCodeLineHookedStackLines method
        // since line number in beforeHook current stack trace may not be set
        // for the root method first line, especially in Sahagin-Groovy

        int thisCodeLineIndex = getHookedCodeLineIndex(hookedTestMethod, hookedLine).get(0);
        if (hookedTestMethod.getCodeBody().get(thisCodeLineIndex).getCode() instanceof TestStepLabel
                || (thisCodeLineIndex > 0
                        && hookedTestMethod.getCodeBody().get(thisCodeLineIndex - 1).getCode() instanceof TestStepLabel)) {
            startTestStepLabelTime = System.currentTimeMillis();
        }

        String codeLineKey = getCodeLineKey(hookedClassQualifiedName, hookedMethodSimpleName,
                hookedArgClassesStr, hookedLine);
        if (startTimeMap.containsKey(codeLineKey)) {
            throw new RuntimeException("codeLineKey is duplicated: " + codeLineKey);
        }
        logger.info(String.format("putting codeLineKey: %s", codeLineKey));
        startTimeMap.put(codeLineKey, System.currentTimeMillis());
    }

    public void afterCodeLineHook(String hookedClassQualifiedName,
            final String hookedMethodSimpleName, final String actualHookedMethodSimpleName,
            String hookedArgClassesStr, final int hookedLine, final int actualHookedLine) {
        if (currentRunResult == null) {
            return; // maybe called outside of the root method
        }

        TestMethod hookedTestMethod = getTestMethod(
                hookedClassQualifiedName, hookedMethodSimpleName, hookedArgClassesStr);
        if (hookedTestMethod == null) {
            return; // hooked method is not current root method
        }

        logger.info(String.format("afterCodeLineHook: start: %s: %d(%d)",
                hookedMethodSimpleName, hookedLine, actualHookedLine));

        List<StackLine> thisStackLines = getCodeLineHookedStackLines(
                hookedMethodSimpleName, actualHookedMethodSimpleName, hookedLine, actualHookedLine);

        String codeLineKey = getCodeLineKey(hookedClassQualifiedName, hookedMethodSimpleName,
                hookedArgClassesStr, hookedLine);
        Long startTime = startTimeMap.get(codeLineKey);
        if (startTime == null) {
            // maybe beforeHook for codeLineKey has not been called unexpectedly
            throw new RuntimeException("codeLineKey not found: " + codeLineKey);
        }
        int executionTime = (int) (System.currentTimeMillis() - startTime);
        startTimeMap.remove(codeLineKey);

        // calculate capturesThisLine value
        CodeLine thisCodeLine = thisStackLines.get(0).getMethod().getCodeBody().get(
                thisStackLines.get(0).getCodeBodyIndex());
        boolean capturesThisLine;
        if (thisCodeLine.getCode() instanceof SubMethodInvoke) {
            SubMethodInvoke thisMethodInvoke = (SubMethodInvoke) thisCodeLine.getCode();
            CaptureStyle thisCaptureStyle = thisMethodInvoke.getSubMethod().getCaptureStyle();
            capturesThisLine
            = (thisCaptureStyle == CaptureStyle.THIS_LINE || thisCaptureStyle == CaptureStyle.STEP_IN);
        } else if (thisCodeLine.getCode() instanceof VarAssign) {
            VarAssign assign = (VarAssign) thisCodeLine.getCode();
            if (assign.getValue() instanceof SubMethodInvoke) {
                SubMethodInvoke thisMethodInvoke = (SubMethodInvoke) assign.getValue();
                CaptureStyle thisCaptureStyle = thisMethodInvoke.getSubMethod().getCaptureStyle();
                capturesThisLine
                = (thisCaptureStyle == CaptureStyle.THIS_LINE || thisCaptureStyle == CaptureStyle.STEP_IN);
            } else if (assign.getVariable() instanceof Field) {
                capturesThisLine = true;
            } else {
                capturesThisLine = false;
            }
        } else if (thisCodeLine.getCode() instanceof TestStep) {
            throw new RuntimeException("not supported");
        } else {
            // don't take screenshot for this line
            capturesThisLine = false;
        }

        // Calculate testStepLabelStackLines, testStepLabelExecutionTime and capturesTestStepLabel.
        // Since screen capture for TestStepLabel is taken at the last line of the TestStepLabel block,
        // capturesTestStepLabel is set true only for the last line of the TestStepLabel block.
        List<StackLine> testStepLabelStackLines = null;
        int testStepLabelExecutionTime = -1;
        boolean capturesTestStepLabel = false;
        int stepLabelIndex = getTestStepLabelIndexIfThisLineIsStepLastCode(
                hookedTestMethod, thisStackLines.get(0).getCodeBodyIndex());
        if (stepLabelIndex != -1) {
            capturesTestStepLabel = true;
            // testStepLabelStackLines can be obtained
            // by changing the top element of thisStackLines
            CodeLine stepLabelCodeLine = hookedTestMethod.getCodeBody().get(stepLabelIndex);
            testStepLabelStackLines = new ArrayList<StackLine>(thisStackLines.size());
            for (StackLine stackLine : thisStackLines) {
                // clone and add new StackLine instance
                testStepLabelStackLines.add(new StackLine(stackLine));
            }
            StackLine topStackLine = testStepLabelStackLines.get(0);
            topStackLine.setLine(stepLabelCodeLine.getStartLine());
            topStackLine.setCodeBodyIndex(stepLabelIndex);

            testStepLabelExecutionTime = (int) (System.currentTimeMillis() - startTestStepLabelTime);
        }

        if (!capturesThisLine && !capturesTestStepLabel) {
            logger.info("afterCodeLineHook: skip not capture line");
            return;
        }

        if (!canStepInCaptureTo(thisStackLines)) {
            logger.info("afterCodeLineHook: skip not stepInCapture line");
            return;
        }

        // screen capture.
        // Takes this line screen capture and TestStepLabel block screen capture at the same time.
        List<List<StackLine>> stackLinesList = new ArrayList<List<StackLine>>(2);
        List<Integer> executionTimeList = new ArrayList<Integer>(2);
        if (capturesThisLine) {
            stackLinesList.add(thisStackLines);
            executionTimeList.add(executionTime);
        }
        if (capturesTestStepLabel) {
            stackLinesList.add(testStepLabelStackLines);
            executionTimeList.add(testStepLabelExecutionTime);
        }
        File captureFile = captureScreenForStackLines(currentRunResult.getRootMethod(), stackLinesList, executionTimeList);
        if (captureFile != null) {
            if (capturesThisLine) {
                logger.info("afterCodeLineHook: end with this line capture " + captureFile.getName());
            }
            if (capturesTestStepLabel) {
                logger.info("afterCodeLineHook: end with TestStepLabel capture " + captureFile.getName());
            }
        }
    }

    // Calculate unique key for each code line hook
    private String getCodeLineKey(final String hookedClassQualifiedName,
                                  final String hookedMethodSimpleName,
                                  String hookedArgClassesStr, final int hookedLine) {
        return String.format("%s_%s_%s_%d_%d",
                hookedClassQualifiedName,
                hookedMethodSimpleName,
                hookedArgClassesStr,
                hookedLine,
                Thread.currentThread().getStackTrace().length);
    }

    // returns null if not executed
    private File captureScreen(TestMethod rootMethod) {
        byte[] screenData = AdapterContainer.globalInstance().captureScreen();
        if (screenData == null) {
            return null;
        }

        // use encoded name to avoid various possible file name encoding problem
        // and to escape invalid file name character (Method name may contain such characters
        // if method is Groovy method, for example).
        File captureFile = new File(String.format("%s/%s/%s/%03d.png", captureRootDir,
                CommonUtils.encodeToSafeAsciiFileNameString(rootMethod.getTestClass().getQualifiedName(), Charsets.UTF_8),
                CommonUtils.encodeToSafeAsciiFileNameString(rootMethod.getSimpleName(), Charsets.UTF_8),
                currentCaptureNo));
        currentCaptureNo++;

        if (captureFile.getParentFile() != null) {
            captureFile.getParentFile().mkdirs();
        }
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(captureFile);
            stream.write(screenData);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            IOUtils.closeQuietly(stream);
        }
        return captureFile;
    }

    // Take a screenshot for all stackLines in stackLinesList.
    // This screenshot is taken for multiple stackLines.
    // - returns screen capture file.
    // - returns null if fails to capture
    private File captureScreenForStackLines(
            TestMethod rootMethod, List<List<StackLine>> stackLinesList, List<Integer> executionTimes) {
        if (stackLinesList == null) {
            throw new NullPointerException();
        }
        if (stackLinesList.size() == 0) {
            throw new IllegalArgumentException("empty list");
        }
        if (stackLinesList.size() != executionTimes.size()) {
            throw new IllegalArgumentException("size mismatch");
        }
        File captureFile = captureScreen(rootMethod);
        if (captureFile == null) {
            return null;
        }
        for (int i = 0; i < stackLinesList.size(); i++) {
            LineScreenCapture capture = new LineScreenCapture();
            capture.setPath(new File(captureFile.getAbsolutePath()));
            capture.addAllStackLines(stackLinesList.get(i));
            capture.setExecutionTime(executionTimes.get(i));
            currentRunResult.addLineScreenCapture(capture);
        }
        return captureFile;
    }

    // if method is called from not stepInCapture line, then returns false.
    private boolean canStepInCaptureTo(List<StackLine> stackLines) {
        // stack bottom line ( = root line) is always regarded as stepIn true line
        for (int i = 0; i < stackLines.size() - 1; i++) {
            StackLine stackLine = stackLines.get(i);
            CaptureStyle style = stackLine.getMethod().getCaptureStyle();
            if (style != CaptureStyle.STEP_IN && style != CaptureStyle.STEP_IN_ONLY) {
                return false;
            }
        }
        return true;
    }

}

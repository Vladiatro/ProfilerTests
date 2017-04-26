package net.falsetrue;

import com.sun.jdi.*;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.connect.VMStartException;
import com.sun.jdi.event.*;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Runs {@link net.falsetrue.Test}, profiling creations of {@link net.falsetrue.Item} using constructor breakpoints
 * @author Vlad Myachikov
 */
public class BreakpointsMonitoringTest {
    private static boolean outputEnabled = false;

    /**
     * Outputs process' stdout and stderr to profiler's output streams
     */
    private static void enableOutput(VirtualMachine vm) {
        if (!outputEnabled) {
            new Thread(() -> {
                Scanner scanner = new Scanner(vm.process().getInputStream());
                while (scanner.hasNext()) {
                    System.out.println(scanner.nextLine());
                }
            }).start();
            new Thread(() -> {
                Scanner scanner = new Scanner(vm.process().getErrorStream());
                while (scanner.hasNext()) {
                    System.err.println(scanner.nextLine());
                }
            }).start();
            outputEnabled = true;
        }
    }

    public static void main(String[] args) throws Exception {
        long mainLocation = 0;
        long itemLocation = 0;
        ReferenceType item = null;
        long creations = 0, existing = 0;

        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> argumentMap = connector.defaultArguments();
        argumentMap.get("main").setValue("net.falsetrue.Test 1 100000");
        VirtualMachine vm = connector.launch(argumentMap);
        EventRequestManager requestManager = vm.eventRequestManager();
        ClassPrepareRequest prepareRequest = requestManager.createClassPrepareRequest();
        prepareRequest.enable();
//        enableOutput(vm);
        try {
            m: while (true) {
                EventSet events = vm.eventQueue().remove();
                for (Event event : events) {
                    if (event instanceof ClassPrepareEvent) {
                        ReferenceType referenceType = ((ClassPrepareEvent) event).referenceType();
                        switch (referenceType.name()) {
                            case "net.falsetrue.Test":
                                // put a breakpoint to the end of main() method to output the result
                                for (Method method : referenceType.methodsByName("main")) {
                                    List<Location> locations = method.allLineLocations();
                                    mainLocation = locations.get(locations.size() - 1).codeIndex();
                                    requestManager.createBreakpointRequest(locations.get(locations.size() - 1)).enable();
                                }
                                break;
                            case "net.falsetrue.Item":
                                item = referenceType;
                                // put breakpoints on Item's constructors
                                for (Method method : referenceType.methodsByName("<init>")) {
                                    itemLocation = method.location().codeIndex();
                                    requestManager.createBreakpointRequest(method.location()).enable();
                                }
                                break;
                        }
                    } else if (event instanceof VMDisconnectEvent) {
                        break m;
                    } else if (event instanceof BreakpointEvent) {
                        BreakpointEvent breakpointEvent = (BreakpointEvent) event;
                        if (breakpointEvent.location().codeIndex() == itemLocation) { // constructor if Item()
                            creations++;
                        } else if (breakpointEvent.location().codeIndex() == mainLocation) { // end of main()
                            existing = vm.instanceCounts(Collections.singletonList(item))[0];
                        }
                    }
                }
                events.resume();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        int exitValue = vm.process().waitFor();
        if (exitValue != 0) {
            System.err.printf("Process ended unsuccessfully with exit value %d. " +
                "Check if net.falsetrue.Test is available from launching classpath.\n", exitValue);
            enableOutput(vm);
        } else {
            System.out.printf("Creations: %d, existing: %d\n", creations, existing);
        }
    }
}

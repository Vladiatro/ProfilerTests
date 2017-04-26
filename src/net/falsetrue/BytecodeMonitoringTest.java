package net.falsetrue;

import com.sun.jdi.*;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static jdk.internal.org.objectweb.asm.Opcodes.*;

/**
 * Runs {@link net.falsetrue.Test}, profiling creations of {@link net.falsetrue.Item} using bytecode insertion
 * @author Vlad Myachikov
 */
public class BytecodeMonitoringTest {
    // information container class names (in VM and language formats) and counter field name
    private static final String COUNTER_CLASS_SLASHES_NAME = "d@/d@/@Counter@";
    private static final String COUNTER_CLASS_DOTS_NAME = "d@.d@.@Counter@";
    private static final String COUNTER_FIELD_NAME = "count";

    private static boolean counterInserted = false;

    public static class WatchingMethodVisitor extends MethodVisitor {
        public WatchingMethodVisitor(int api, MethodVisitor mv) {
            super(api, mv);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            // when we visit "NEW net/falsetrue/Item", insert code that increments the counter
            if (opcode == NEW && type.equals("net/falsetrue/Item")) {
                visitFieldInsn(GETSTATIC, COUNTER_CLASS_SLASHES_NAME, COUNTER_FIELD_NAME, "I");
                visitInsn(ICONST_1);
                visitInsn(IADD);
                visitFieldInsn(PUTSTATIC, COUNTER_CLASS_SLASHES_NAME, COUNTER_FIELD_NAME, "I");
            }
            super.visitTypeInsn(opcode, type);
        }
    }

    public static class WatchingClassVisitor extends ClassVisitor {
        public WatchingClassVisitor(int api, ClassWriter classWriter) {
            super(api, classWriter);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {

            MethodVisitor methodVisitor = super.visitMethod(access, name, desc, signature, exceptions);
            return new WatchingMethodVisitor(super.api, methodVisitor);
        }
    }

    private static void insertCounterClass(VirtualMachine vm, ClassLoaderReference loaderReference,
                                           ThreadReference threadReference) throws Exception {
        // bytecode for d@/d@/@Counter@ class:
        // package d@.d@;
        // public class @Counter@ {
        //     public static int count;
        //     public @Counter@() {}
        // }
        byte[] bytes = {-54, -2, -70, -66, 0, 0, 0, 52, 0, 18, 10, 0, 3, 0, 15, 7, 0, 16, 7, 0, 17, 1, 0, 5, 99, 111, 117, 110, 116, 1, 0, 1, 73, 1, 0, 6, 60, 105, 110, 105, 116, 62, 1, 0, 3, 40, 41, 86, 1, 0, 4, 67, 111, 100, 101, 1, 0, 15, 76, 105, 110, 101, 78, 117, 109, 98, 101, 114, 84, 97, 98, 108, 101, 1, 0, 18, 76, 111, 99, 97, 108, 86, 97, 114, 105, 97, 98, 108, 101, 84, 97, 98, 108, 101, 1, 0, 4, 116, 104, 105, 115, 1, 0, 17, 76, 100, 64, 47, 100, 64, 47, 64, 67, 111, 117, 110, 116, 101, 114, 64, 59, 1, 0, 10, 83, 111, 117, 114, 99, 101, 70, 105, 108, 101, 1, 0, 14, 64, 67, 111, 117, 110, 116, 101, 114, 64, 46, 106, 97, 118, 97, 12, 0, 6, 0, 7, 1, 0, 15, 100, 64, 47, 100, 64, 47, 64, 67, 111, 117, 110, 116, 101, 114, 64, 1, 0, 16, 106, 97, 118, 97, 47, 108, 97, 110, 103, 47, 79, 98, 106, 101, 99, 116, 0, 33, 0, 2, 0, 3, 0, 0, 0, 1, 0, 9, 0, 4, 0, 5, 0, 0, 0, 1, 0, 1, 0, 6, 0, 7, 0, 1, 0, 8, 0, 0, 0, 47, 0, 1, 0, 1, 0, 0, 0, 5, 42, -73, 0, 1, -79, 0, 0, 0, 2, 0, 9, 0, 0, 0, 6, 0, 1, 0, 0, 0, 3, 0, 10, 0, 0, 0, 12, 0, 1, 0, 0, 0, 5, 0, 11, 0, 12, 0, 0, 0, 1, 0, 13, 0, 0, 0, 2, 0, 14};
        ArrayType byteArrayType = (ArrayType) vm.classesByName("byte[]").get(0);
        ArrayReference arrayReference = byteArrayType.newInstance(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            arrayReference.setValue(i, vm.mirrorOf(bytes[i]));
        }
        Method defineClassMethod = loaderReference.referenceType()
            .methodsByName("defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;").get(0);
        List<Value> arguments = new ArrayList<>();
        arguments.add(vm.mirrorOf(COUNTER_CLASS_DOTS_NAME));
        arguments.add(arrayReference);
        arguments.add(vm.mirrorOf(0));
        arguments.add(vm.mirrorOf(bytes.length));

        try {
            loaderReference.invokeMethod(threadReference, defineClassMethod, arguments, 0);
        } catch (InvocationException e) {
            ReferenceType referenceType = e.exception().referenceType();
            System.err.println(referenceType);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void redefineIfNeeded(ReferenceType type, VirtualMachine vm) throws IOException {
        if (type.name().equals("net.falsetrue.Test")) {
            InputStream in = BytecodeMonitoringTest.class.getResourceAsStream("/net/falsetrue/Test.class");
            ClassReader classReader = new ClassReader(in);
            ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            WatchingClassVisitor watchingClassVisitor = new WatchingClassVisitor(ASM4, classWriter);
            classReader.accept(watchingClassVisitor, 0);
            vm.redefineClasses(Collections.singletonMap(type, classWriter.toByteArray()));
        }
    }

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
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> argumentMap = connector.defaultArguments();
        argumentMap.get("main").setValue("net.falsetrue.Test 10 1000000");
        VirtualMachine vm = connector.launch(argumentMap);
        EventRequestManager requestManager = vm.eventRequestManager();
        ClassPrepareRequest prepareRequest = requestManager.createClassPrepareRequest();
        prepareRequest.addClassFilter("net.falsetrue.Test");
        prepareRequest.enable();
//        enableOutput(vm);
        long creations = 0, existing = 0;
        m: while (true) {
            EventSet events = vm.eventQueue().remove();
            for (Event event : events) {
                if (event instanceof ClassPrepareEvent) {
                    ReferenceType referenceType = ((ClassPrepareEvent) event).referenceType();
                    if (!counterInserted) {
                        counterInserted = true;
                        insertCounterClass(vm, referenceType.classLoader(), ((ClassPrepareEvent) event).thread());
                    }
                    redefineIfNeeded(referenceType, vm);

                    // put a breakpoint to the end of main() method to output the result
                    List<Location> locations = referenceType.methodsByName("main").get(0).allLineLocations();
                    requestManager.createBreakpointRequest(locations.get(locations.size() - 1)).enable();
                } else if (event instanceof VMDisconnectEvent) {
                    events.resume();
                    break m;
                } else if (event instanceof BreakpointEvent) {
                    // end of main()
                    // retrieve creations count from the information class
                    ReferenceType counter = vm.classesByName(COUNTER_CLASS_SLASHES_NAME).get(0);
                    Field field = counter.fieldByName(COUNTER_FIELD_NAME);
                    creations = ((IntegerValue) counter.getValue(field)).value();

                    // retrieve existing objects count
                    existing = vm.instanceCounts(Collections.singletonList(vm.classesByName("net.falsetrue.Item").get(0)))[0];
                }
            }
            events.resume();
        }
        int exitValue = vm.process().waitFor();
        if (exitValue != 0) {
            System.err.printf("Process ended unsuccessfully with exit value of %d. " +
                "Check if net.falsetrue.Test is available from launching classpath.\n", exitValue);
            enableOutput(vm);
        } else {
            System.out.printf("Creations: %d, existing: %d\n", creations, existing);
        }
    }
}

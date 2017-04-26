#include <stdio.h>
#include <jvmti.h>
#include <memory.h>
#include <stdlib.h>
#include "string.h"

/**
 * This is the agent which runs {@link net.falsetrue.Test},
 * and profile creations of {@link net.falsetrue.Item} using constructor breakpoints.
 *
 * Compile it into a shared library and run Test using:
 * java -agentpath:/path/to/library net.falsetrue.Test 10 1000000
 *
 * \authors Vlad Myachikov
 */

int creations = 0;
int existing = 0;
jmethodID constructor;
jmethodID main;
jclass itemClass;

JNIEXPORT void check_jvmti_error(jvmtiEnv *env, jvmtiError error, char *string) {
    if (error != JVMTI_ERROR_NONE) {
        printf("Error %d: %s\n", error, string);
        exit(1);
    }
}

void JNICALL classPrepare(jvmtiEnv *jvmti,
          JNIEnv* jni_env,
          jthread thread,
          jclass klass) {
    char* signature = NULL;
    char* devnull = NULL;
    jint methodsCount;
    jmethodID* methods = NULL;
    jvmtiError error;
    error = (*jvmti)->GetClassSignature(jvmti, klass, &signature, &devnull);
    if (strcmp(signature, "Lnet/falsetrue/Item;") == 0) {
        itemClass = klass;
        error = (*jvmti)->GetClassMethods(jvmti, klass, &methodsCount, &methods);
        for (int i = 0; i < methodsCount; i++) {
            char* name;
            error = (*jvmti)->GetMethodName(jvmti, methods[i], &name, &devnull, &devnull);
            if (strcmp(name, "<init>") == 0) {
                constructor = methods[i];
                jlocation start;
                jlocation end;
                error = (*jvmti)->GetMethodLocation(jvmti, methods[i], &start, &end);
                error = (*jvmti)->SetBreakpoint(jvmti, methods[i], start);
            }
        }
    }
    if (strcmp(signature, "Lnet/falsetrue/Test;") == 0) {
        error = (*jvmti)->GetClassMethods(jvmti, klass, &methodsCount, &methods);
        for (int i = 0; i < methodsCount; i++) {
            char* name;
            error = (*jvmti)->GetMethodName(jvmti, methods[i], &name, &devnull, &devnull);
            if (strcmp(name, "main") == 0) {
                main = methods[i];
                jlocation start;
                jlocation end;
                error = (*jvmti)->GetMethodLocation(jvmti, methods[i], &start, &end);
                error = (*jvmti)->SetBreakpoint(jvmti, methods[i], end);
            }
        }
    }
}

jint JNICALL heapIteration(jlong class_tag,
                           jlong size,
                           jlong* tag_ptr,
                           jint length,
                           void* user_data) {
    existing++;
    return JVMTI_VISIT_OBJECTS;
}

// breakpoint event handler
void JNICALL breakpoint(jvmtiEnv *jvmti,
                   JNIEnv* jni_env,
                   jthread thread,
                   jmethodID method,
                   jlocation location) {
    if (method == main) {
        jvmtiHeapCallbacks callbacks;
        (void)memset(&callbacks, 0, sizeof(jvmtiHeapCallbacks));
        jvmtiError error;
        callbacks.heap_iteration_callback = &heapIteration;
        error = (*jvmti)->IterateThroughHeap(jvmti, 0, itemClass, &callbacks, NULL);
        check_jvmti_error(jvmti, error, "error 100505");
    } else {
        creations++;
    }
}

void JNICALL vmDeath(jvmtiEnv *jvmti_env, JNIEnv* jni_env) {
    printf("Creations: %d, existing: %d\n", creations, existing);
}

// the main agent function
jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
    static jvmtiEnv *jvmti = NULL;
    static jvmtiCapabilities capabilities;
    jvmtiError error;
    jint result = (*jvm)->GetEnv(jvm, (void **) &jvmti, JVMTI_VERSION_1_1);
    if (result != JNI_OK) {
        printf("ERROR: Unable to access JVMTI!\n");
    }
    (void)memset(&capabilities, 0, sizeof(jvmtiCapabilities));
    capabilities.can_generate_breakpoint_events = 1;
    capabilities.can_generate_field_access_events = 1;
    capabilities.can_tag_objects = 1;
    error = (*jvmti)->AddCapabilities(jvmti, &capabilities);
    error = (*jvmti)->SetEventNotificationMode
            (jvmti, JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, (jthread)NULL);
    error = (*jvmti)->SetEventNotificationMode
            (jvmti, JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, (jthread)NULL);
    error = (*jvmti)->SetEventNotificationMode
            (jvmti, JVMTI_ENABLE, JVMTI_EVENT_VM_DEATH, (jthread)NULL);
    check_jvmti_error(jvmti, error, "Unable to get necessary JVMTI capabilities.");

    jvmtiEventCallbacks callbacks;
    (void)memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ClassPrepare = &classPrepare;
    callbacks.Breakpoint = &breakpoint;
    callbacks.VMDeath = &vmDeath;
    error = (*jvmti)->SetEventCallbacks(jvmti, &callbacks,(jint)sizeof(callbacks));
    check_jvmti_error(jvmti, error, "Cannot set jvmti callbacks");
    return JNI_OK;
}
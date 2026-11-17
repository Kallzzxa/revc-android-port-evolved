//
// Created by mrxenginner on 14/07/2025.
//

#ifndef REVC_CJAVAWRAPPER_H
#define REVC_CJAVAWRAPPER_H

#if defined ANDROID

#include <jni.h>
#include <string>

#define EXCEPTION_CHECK(env) \
	if ((env)->ExceptionCheck()) \
	{ \
		(env)->ExceptionDescribe(); \
		(env)->ExceptionClear(); \
		return; \
	}

class CJavaWrapper
{
    jmethodID s_ExitGame;
    jmethodID s_SetLoadingText;
    jmethodID s_HideLoading;
public:
    jobject activity;

    CJavaWrapper(JNIEnv* env, jobject activity);
    ~CJavaWrapper();

    void ExitGame();

    // Loading overlay (GameActivity): the engine start-up -- in
    // particular the first-run audio cache scan -- runs on the native
    // thread with a black screen, so report progress to the overlay
    // instead of looking hung.
    void SetLoadingText(const char* text);
    void HideLoading();

    // static methods
    static JNIEnv* GetEnv();
};

// Free functions so core code can reach the overlay without including
// this header (forward-declared at the call sites).
void AndroidLoadingSetText(const char* text);
void AndroidLoadingHide();

extern CJavaWrapper* g_pJavaWrapper;
#endif

#endif //REVC_CJAVAWRAPPER_H

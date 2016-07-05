package com.jme3.system.android;

/*
 * Copyright (c) 2009-2012 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import com.google.vr.sdk.base.Eye;
import com.google.vr.sdk.base.GvrView;
import com.google.vr.sdk.base.HeadTransform;
import com.google.vr.sdk.base.Viewport;
import com.jme3.app.LegacyApplication;
import com.jme3.input.*;
import com.jme3.input.android.AndroidInputHandler;
import com.jme3.input.controls.SoftTextDialogInputListener;
import com.jme3.input.dummy.DummyKeyInput;
import com.jme3.input.dummy.DummyMouseInput;
import com.jme3.renderer.android.AndroidGL;
import com.jme3.renderer.opengl.GL;
import com.jme3.renderer.opengl.GLExt;
import com.jme3.renderer.opengl.GLFbo;
import com.jme3.renderer.opengl.GLRenderer;
import com.jme3.system.*;

import javax.microedition.khronos.egl.EGLConfig;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author oak
 */
public class GvrOGLESContext implements JmeContext, GvrView.StereoRenderer, SoftTextDialogInput {

    private static final Logger logger = Logger.getLogger(GvrOGLESContext.class.getName());
    protected final AtomicBoolean created = new AtomicBoolean(false);
    protected final AtomicBoolean renderable = new AtomicBoolean(false);
    protected final AtomicBoolean needClose = new AtomicBoolean(false);
    protected AppSettings settings = new AppSettings(true);

    protected GLRenderer renderer;
    protected Timer timer;
    protected SystemListener listener;
    protected boolean autoFlush = true;
    protected AndroidInputHandler androidInput;
    protected long minFrameDuration = 0;                   // No FPS cap
    protected long lastUpdateTime = 0;
    private LegacyApplication app;

    public GvrOGLESContext() {
    }

    @Override
    public Type getType() {
        return Type.Display;
    }

    protected void initInThread() {
        created.set(true);

        logger.fine("GvrOGLESContext create");
        logger.log(Level.FINE, "Running on thread: {0}", Thread.currentThread().getName());

        // Setup unhandled Exception Handler
        Thread.currentThread().setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread thread, Throwable thrown) {
                listener.handleError("Exception thrown in " + thread.toString(), thrown);
            }
        });

        timer = new NanoTimer();
        Object gl = new AndroidGL();
        // gl = GLTracer.createGlesTracer((GL)gl, (GLExt)gl);
        // gl = new GLDebugES((GL)gl, (GLExt)gl);
        renderer = new GLRenderer((GL)gl, (GLExt)gl, (GLFbo)gl);
        renderer.initialize();

        JmeSystem.setSoftTextDialogInput(this);

        needClose.set(false);
    }

    /**
     * De-initialize in the OpenGL thread.
     */
    protected void deinitInThread() {
        if (renderable.get()) {
            created.set(false);
            if (renderer != null) {
                renderer.cleanup();
            }

            listener.destroy();

            listener = null;
            renderer = null;
            timer = null;

            // do android specific cleaning here
            logger.fine("Display destroyed.");

            renderable.set(false);
        }
    }

    @Override
    public void setSettings(AppSettings settings) {
        this.settings.copyFrom(settings);
        if (androidInput != null) {
            androidInput.loadSettings(settings);
        }

        if (settings.getFrameRate() > 0) {
            minFrameDuration = (long)(1000d / (double)settings.getFrameRate()); // ms
            logger.log(Level.FINE, "Setting min tpf: {0}ms", minFrameDuration);
        } else {
            minFrameDuration = 0;
        }
    }

    @Override
    public void setSystemListener(SystemListener listener) {
        this.listener = listener;
    }

    @Override
    public AppSettings getSettings() {
        return settings;
    }

    @Override
    public com.jme3.renderer.Renderer getRenderer() {
        return renderer;
    }

    @Override
    public MouseInput getMouseInput() {
        return new DummyMouseInput();
    }

    @Override
    public KeyInput getKeyInput() {
        return new DummyKeyInput();
    }

    @Override
    public JoyInput getJoyInput() {
        /* FIXME: We have not touch input :-(
        return androidInput.getJoyInput();
         */
        return null;
    }

    @Override
    public TouchInput getTouchInput() {
        /* FIXME: We have not touch input :-(
        return androidInput.getTouchInput();
         */
        return null;
    }

    @Override
    public Timer getTimer() {
        return timer;
    }

    @Override
    public void setTitle(String title) {
    }

    @Override
    public boolean isCreated() {
        return created.get();
    }

    @Override
    public void setAutoFlushFrames(boolean enabled) {
        this.autoFlush = enabled;
    }

    @Override
    public boolean isRenderable() {
        return renderable.get();
    }

    @Override
    public void create(boolean waitFor) {
        if (waitFor) {
            waitFor(true);
        }
    }

    public void create() {
        create(false);
    }

    @Override
    public void restart() {
    }

    @Override
    public void destroy(boolean waitFor) {
        needClose.set(true);
        if (waitFor) {
            waitFor(false);
        }
    }

    public void destroy() {
        destroy(true);
    }

    protected void waitFor(boolean createdVal) {
        while (renderable.get() != createdVal) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
            }
        }
    }

    public void requestDialog(final int id, final String title, final String initialValue, final SoftTextDialogInputListener listener) {
        throw new RuntimeException("FIXME: we cannot do a dialog right now");
        /* FIXME: This is not good, we should draw this in 3D-Space somehow
        logger.log(Level.FINE, "requestDialog: title: {0}, initialValue: {1}",
                new Object[]{title, initialValue});

        final View view = JmeAndroidSystem.getView();
        view.getHandler().post(new Runnable() {
            @Override
            public void run() {

                final FrameLayout layoutTextDialogInput = new FrameLayout(view.getContext());
                final EditText editTextDialogInput = new EditText(view.getContext());
                editTextDialogInput.setWidth(ViewGroup.LayoutParams.FILL_PARENT);
                editTextDialogInput.setHeight(ViewGroup.LayoutParams.FILL_PARENT);
                editTextDialogInput.setPadding(20, 20, 20, 20);
                editTextDialogInput.setGravity(Gravity.FILL_HORIZONTAL);
                //editTextDialogInput.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);

                editTextDialogInput.setText(initialValue);

                switch (id) {
                    case SoftTextDialogInput.TEXT_ENTRY_DIALOG:

                        editTextDialogInput.setInputType(InputType.TYPE_CLASS_TEXT);
                        break;

                    case SoftTextDialogInput.NUMERIC_ENTRY_DIALOG:

                        editTextDialogInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
                        break;

                    case SoftTextDialogInput.NUMERIC_KEYPAD_DIALOG:

                        editTextDialogInput.setInputType(InputType.TYPE_CLASS_PHONE);
                        break;

                    default:
                        break;
                }

                layoutTextDialogInput.addView(editTextDialogInput);

                AlertDialog dialogTextInput = new AlertDialog.Builder(view.getContext()).setTitle(title).setView(layoutTextDialogInput).setPositiveButton("OK",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                // User clicked OK, send COMPLETE action and text
                                listener.onSoftText(SoftTextDialogInputListener.COMPLETE, editTextDialogInput.getText().toString());
                            }
                        }).setNegativeButton("Cancel",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                // User clicked CANCEL, send CANCEL action and text
                                listener.onSoftText(SoftTextDialogInputListener.CANCEL, editTextDialogInput.getText().toString());
                            }
                        }).create();

                dialogTextInput.show();
            }
        });
        */
    }

    float[] headRotation = new float[4];
    @Override
    public void onNewFrame(HeadTransform headTransform) {
        // Build the camera matrix and apply it to the ModelView.
        if(app.getCamera() != null)
        {
            headTransform.getQuaternion(headRotation, 0);
            app.getCamera().getRotation().set(headRotation[3], headRotation[2], headRotation[1], headRotation[0]);
            app.getCamera().onFrameChange();
        }
    }

    // SystemListener:update
    @Override
    public void onDrawEye(Eye eye) {
        // FIXME: Move the updating part into onNewFrame(...)

        if (needClose.get()) {
            deinitInThread();
            return;
        }

        if (!renderable.get()) {
            if (created.get()) {
                logger.fine("GL Surface is setup, initializing application");
                listener.initialize();
                renderable.set(true);
            }
        } else {
            if (!created.get()) {
                throw new IllegalStateException("onDrawFrame without create");
            }

            listener.update();
            if (autoFlush) {
                renderer.postFrame();
            }

            /* TODO: Check if we really want this
            long updateDelta = System.currentTimeMillis() - lastUpdateTime;

            // Enforce a FPS cap
            if (updateDelta < minFrameDuration) {
//                    logger.log(Level.INFO, "lastUpdateTime: {0}, updateDelta: {1}, minTimePerFrame: {2}",
//                            new Object[]{lastUpdateTime, updateDelta, minTimePerFrame});
                try {
                    Thread.sleep(minFrameDuration - updateDelta);
                } catch (InterruptedException e) {
                }
            }

            lastUpdateTime = System.currentTimeMillis();
            */

        }
    }

    @Override
    public void onFinishFrame(Viewport viewport) {

    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        logger.log(Level.FINE, "GL Surface changed, width: {0} height: {1}", new Object[]{width, height});
        // update the application settings with the new resolution
        settings.setResolution(width, height);

        /* FIXME: We have no input!
        // reload settings in androidInput so the correct touch event scaling can be
        // calculated in case the surface resolution is different than the view
        androidInput.loadSettings(settings);
        */
        // if the application has already been initialized (ie renderable is set)
        // then call reshape so the app can adjust to the new resolution.
        if (renderable.get()) {
            logger.log(Level.FINE, "App already initialized, calling reshape");
            listener.reshape(width, height);
        }
    }

    @Override
    public void onSurfaceCreated(EGLConfig eglConfig) {
        if (created.get() && renderer != null) {
            renderer.resetGLObjects();
        } else {
            if (!created.get()) {
                logger.fine("GL Surface created, initializing JME3 renderer");
                initInThread();
            } else {
                logger.warning("GL Surface already created");
            }
        }
    }

    @Override
    public void onRendererShutdown() {

    }

    public void setApp(LegacyApplication app) {
        this.app = app;
    }
}

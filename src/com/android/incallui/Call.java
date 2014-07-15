/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.incallui;

import android.net.Uri;
import android.telecomm.CallCapabilities;
import android.telecomm.PhoneAccount;
import android.telecomm.RemoteCallVideoProvider;
import android.telecomm.GatewayInfo;
import android.telephony.DisconnectCause;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Describes a single call and its state.
 */
public final class Call {
    /* Defines different states of this call */
    public static class State {
        public static final int INVALID = 0;
        public static final int IDLE = 1;           /* The call is idle.  Nothing active */
        public static final int ACTIVE = 2;         /* There is an active call */
        public static final int INCOMING = 3;       /* A normal incoming phone call */
        public static final int CALL_WAITING = 4;   /* Incoming call while another is active */
        public static final int DIALING = 5;        /* An outgoing call during dial phase */
        public static final int REDIALING = 6;      /* Subsequent dialing attempt after a failure */
        public static final int ONHOLD = 7;         /* An active phone call placed on hold */
        public static final int DISCONNECTING = 8;  /* A call is being ended. */
        public static final int DISCONNECTED = 9;   /* State after a call disconnects */
        public static final int CONFERENCED = 10;   /* Call part of a conference call */

        public static boolean isConnected(int state) {
            switch(state) {
                case ACTIVE:
                case INCOMING:
                case CALL_WAITING:
                case DIALING:
                case REDIALING:
                case ONHOLD:
                case CONFERENCED:
                    return true;
                default:
            }
            return false;
        }

        public static boolean isDialing(int state) {
            return state == DIALING || state == REDIALING;
        }

        public static String toString(int state) {
            switch (state) {
                case INVALID:
                    return "INVALID";
                case IDLE:
                    return "IDLE";
                case ACTIVE:
                    return "ACTIVE";
                case INCOMING:
                    return "INCOMING";
                case CALL_WAITING:
                    return "CALL_WAITING";
                case DIALING:
                    return "DIALING";
                case REDIALING:
                    return "REDIALING";
                case ONHOLD:
                    return "ONHOLD";
                case DISCONNECTING:
                    return "DISCONNECTING";
                case DISCONNECTED:
                    return "DISCONNECTED";
                case CONFERENCED:
                    return "CONFERENCED";
                default:
                    return "UNKOWN";
            }
        }
    }

    private static final String ID_PREFIX = Call.class.getSimpleName() + "_";
    private static int sIdCounter = 0;

    private android.telecomm.Call.Listener mTelecommCallListener =
            new android.telecomm.Call.Listener() {
                @Override
                public void onStateChanged(android.telecomm.Call call, int newState) {
                    update();
                }

                @Override
                public void onParentChanged(android.telecomm.Call call,
                        android.telecomm.Call newParent) {
                    update();
                }

                @Override
                public void onChildrenChanged(android.telecomm.Call call,
                        List<android.telecomm.Call> children) {
                    update();
                }

                @Override
                public void onDetailsChanged(android.telecomm.Call call,
                        android.telecomm.Call.Details details) {
                    update();
                }

                @Override
                public void onCannedTextResponsesLoaded(android.telecomm.Call call,
                        List<String> cannedTextResponses) {
                    update();
                }

                @Override
                public void onPostDial(android.telecomm.Call call,
                        String remainingPostDialSequence) {
                    update();
                }

                @Override
                public void onPostDialWait(android.telecomm.Call call,
                        String remainingPostDialSequence) {
                    update();
                }

                @Override
                public void onCallVideoProviderChanged(android.telecomm.Call call,
                        RemoteCallVideoProvider callVideoProvider) {
                    update();
                }

                @Override
                public void onCallDestroyed(android.telecomm.Call call) {
                    call.removeListener(mTelecommCallListener);
                }
            };

    private final android.telecomm.Call mTelecommCall;
    private final String mId;
    private int mState = State.INVALID;
    private int mDisconnectCause;
    private String mParentCallId;
    private final List<String> mChildCallIds = new ArrayList<>();
    private int mVideoState;

    private InCallVideoClient mCallVideoClient;

    public Call(android.telecomm.Call telecommCall) {
        mTelecommCall = telecommCall;
        mId = ID_PREFIX + Integer.toString(sIdCounter++);
        updateFromTelecommCall();
        if (getState() == Call.State.INCOMING) {
            CallList.getInstance().onIncoming(this, getCannedSmsResponses());
        } else {
            CallList.getInstance().onUpdate(this);
        }
        mTelecommCall.addListener(mTelecommCallListener);
    }

    public android.telecomm.Call getTelecommCall() {
        return mTelecommCall;
    }

    private void update() {
        int oldState = getState();
        updateFromTelecommCall();
        if (oldState != getState() && getState() == Call.State.DISCONNECTED) {
            CallList.getInstance().onDisconnect(this);
        } else {
            CallList.getInstance().onUpdate(this);
        }
    }

    private void updateFromTelecommCall() {
        setState(translateState(mTelecommCall.getState()));
        setDisconnectCause(mTelecommCall.getDetails().getDisconnectCauseCode());

        if (mTelecommCall.getParent() != null) {
            mParentCallId = CallList.getInstance().getCallByTelecommCall(
                    mTelecommCall.getParent()).getId();
        }

        if (mTelecommCall.getCallVideoProvider() != null) {
            if (mCallVideoClient == null) {
                mCallVideoClient = new InCallVideoClient();
            }
            mTelecommCall.getCallVideoProvider().setCallVideoClient(mCallVideoClient);
        }

        mChildCallIds.clear();
        for (int i = 0; i < mTelecommCall.getChildren().size(); i++) {
            mChildCallIds.add(
                    CallList.getInstance().getCallByTelecommCall(
                            mTelecommCall.getChildren().get(i)).getId());
        }
    }

    private static int translateState(int state) {
        switch (state) {
            case android.telecomm.Call.STATE_DIALING:
            case android.telecomm.Call.STATE_NEW:
                return Call.State.DIALING;
            case android.telecomm.Call.STATE_RINGING:
                return Call.State.INCOMING;
            case android.telecomm.Call.STATE_ACTIVE:
                return Call.State.ACTIVE;
            case android.telecomm.Call.STATE_HOLDING:
                return Call.State.ONHOLD;
            case android.telecomm.Call.STATE_DISCONNECTED:
                return Call.State.DISCONNECTED;
            default:
                return Call.State.INVALID;
        }
    }

    public String getId() {
        return mId;
    }

    public String getNumber() {
        if (mTelecommCall.getDetails().getGatewayInfo() != null) {
            return mTelecommCall.getDetails().getGatewayInfo()
                    .getOriginalHandle().getSchemeSpecificPart();
        }
        return getHandle() == null ? null : getHandle().getSchemeSpecificPart();
    }

    public Uri getHandle() {
        return mTelecommCall.getDetails().getHandle();
    }

    public int getState() {
        if (mParentCallId != null) {
            return State.CONFERENCED;
        } else {
            return mState;
        }
    }

    public void setState(int state) {
        mState = state;
    }

    public int getNumberPresentation() {
        return getTelecommCall().getDetails().getHandlePresentation();
    }

    public int getCnapNamePresentation() {
        return getTelecommCall().getDetails().getCallerDisplayNamePresentation();
    }

    public String getCnapName() {
        return getTelecommCall().getDetails().getCallerDisplayName();
    }

    /** Returns call disconnect cause; values are defined in {@link DisconnectCause}. */
    public int getDisconnectCause() {
        if (mState == State.DISCONNECTED || mState == State.IDLE) {
            return mDisconnectCause;
        }

        return DisconnectCause.NOT_DISCONNECTED;
    }

    public void setDisconnectCause(int disconnectCause) {
        mDisconnectCause = disconnectCause;
    }

    /** Returns the possible text message responses. */
    public List<String> getCannedSmsResponses() {
        return mTelecommCall.getCannedTextResponses();
    }

    /** Checks if the call supports the given set of capabilities supplied as a bit mask. */
    public boolean can(int capabilities) {
        return (capabilities == (capabilities & mTelecommCall.getDetails().getCapabilities()));
    }

    /** Gets the time when the call first became active. */
    public long getConnectTimeMillis() {
        return mTelecommCall.getDetails().getConnectTimeMillis();
    }

    public boolean isConferenceCall() {
        return mChildCallIds != null && !mChildCallIds.isEmpty();
    }

    public GatewayInfo getGatewayInfo() {
        return mTelecommCall.getDetails().getGatewayInfo();
    }

    public PhoneAccount getAccount() {
        return mTelecommCall.getDetails().getAccount();
    }

    public RemoteCallVideoProvider getCallVideoProvider() {
        return mTelecommCall.getCallVideoProvider();
    }

    public List<String> getChildCallIds() {
        return mChildCallIds;
    }

    public String getParentId() {
        return mParentCallId;
    }

    public int getVideoState() {
        return mVideoState;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "[%s, %s, %s, children:%s, parent:%s]",
                mId,
                State.toString(mState),
                CallCapabilities.toString(mTelecommCall.getDetails().getCapabilities()),
                mChildCallIds,
                mParentCallId);
    }
}
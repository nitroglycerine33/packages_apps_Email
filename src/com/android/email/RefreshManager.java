/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.email;

import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;

import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.util.Log;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

/**
 * Class that handles "refresh" (and "send pending messages" for outboxes) related functionalities.
 *
 * <p>This class is responsible for two things:
 * <ul>
 *   <li>Taking refresh requests of mailbox-lists and message-lists and the "send outgoing
 *       messages" requests from UI, and calls appropriate methods of {@link Controller}.
 *       Note at this point the timer-based refresh
 *       (by {@link com.android.email.service.MailService}) uses {@link Controller} directly.
 *   <li>Keeping track of which mailbox list/message list is actually being refreshed.
 * </ul>
 * Refresh requests will be ignored if a request to the same target is already requested, or is
 * already being refreshed.
 *
 * <p>Conceptually it can be a part of {@link Controller}, but extracted for easy testing.
 */
public class RefreshManager {
    private static final boolean DEBUG_CALLBACK_LOG = true;
    private static final long MAILBOX_AUTO_REFRESH_INTERVAL = 5 * 60 * 1000; // in milliseconds

    private static RefreshManager sInstance;

    private final Clock mClock;
    private final Context mContext;
    private final Controller mController;
    private final Controller.Result mControllerResult;

    /** Last error message */
    private String mErrorMessage;

    public interface Listener {
        public void onRefreshStatusChanged(long accountId, long mailboxId);
        public void onMessagingError(long accountId, long mailboxId, String message);
    }

    private final ArrayList<Listener> mListeners = new ArrayList<Listener>();

    /**
     * Status of a mailbox list/message list.
     */
    /* package */ static class Status {
        /**
         * True if a refresh of the mailbox is requested, and not finished yet.
         */
        private boolean mIsRefreshRequested;

        /**
         * True if the mailbox is being refreshed.
         *
         * Set true when {@link #onRefreshRequested} is called, i.e. refresh is requested by UI.
         * Note refresh can occur without a request from UI as well (e.g. timer based refresh).
         * In which case, {@link #mIsRefreshing} will be true with {@link #mIsRefreshRequested}
         * being false.
         */
        private boolean mIsRefreshing;

        private long mLastRefreshTime;

        public boolean isRefreshing() {
            return mIsRefreshRequested || mIsRefreshing;
        }

        public boolean canRefresh() {
            return !isRefreshing();
        }

        public void onRefreshRequested() {
            mIsRefreshRequested = true;
        }

        public long getLastRefreshTime() {
            return mLastRefreshTime;
        }

        public void onCallback(MessagingException exception, int progress, Clock clock) {
            if (exception == null && progress == 0) {
                // Refresh started
                mIsRefreshing = true;
            } else if (exception != null || progress == 100) {
                // Refresh finished
                mIsRefreshing = false;
                mIsRefreshRequested = false;
                mLastRefreshTime = clock.getTime();
            }
        }
    }

    /**
     * Map of accounts/mailboxes to {@link Status}.
     */
    private static class RefreshStatusMap {
        private final HashMap<Long, Status> mMap = new HashMap<Long, Status>();

        public Status get(long id) {
            Status s = mMap.get(id);
            if (s == null) {
                s = new Status();
                mMap.put(id, s);
            }
            return s;
        }

        public boolean isRefreshingAny() {
            for (Status s : mMap.values()) {
                if (s.isRefreshing()) {
                    return true;
                }
            }
            return false;
        }
    }

    private final RefreshStatusMap mMailboxListStatus = new RefreshStatusMap();
    private final RefreshStatusMap mMessageListStatus = new RefreshStatusMap();
    private final RefreshStatusMap mOutboxStatus = new RefreshStatusMap();

    /**
     * @return the singleton instance.
     */
    public static synchronized RefreshManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new RefreshManager(context, Controller.getInstance(context),
                    Clock.INSTANCE, new Handler());
        }
        return sInstance;
    }

    /* package */ RefreshManager(Context context, Controller controller, Clock clock,
            Handler handler) {
        mClock = clock;
        mContext = context.getApplicationContext();
        mController = controller;
        mControllerResult = new ControllerResultUiThreadWrapper<ControllerResult>(
                handler, new ControllerResult());
        mController.addResultCallback(mControllerResult);
    }

    public void registerListener(Listener listener) {
        if (listener == null) {
            throw new InvalidParameterException();
        }
        mListeners.add(listener);
    }

    public void unregisterListener(Listener listener) {
        if (listener == null) {
            throw new InvalidParameterException();
        }
        mListeners.remove(listener);
    }

    /**
     * Refresh the mailbox list of an account.
     */
    public boolean refreshMailboxList(long accountId) {
        final Status status = mMailboxListStatus.get(accountId);
        if (!status.canRefresh()) return false;

        Log.i(Email.LOG_TAG, "refreshMailboxList " + accountId);
        status.onRefreshRequested();
        notifyRefreshStatusChanged(accountId, -1);
        mController.updateMailboxList(accountId);
        return true;
    }

    public boolean isMailboxStale(long mailboxId) {
        return mClock.getTime() >= (mMessageListStatus.get(mailboxId).getLastRefreshTime()
                + MAILBOX_AUTO_REFRESH_INTERVAL);
    }

    /**
     * Refresh messages in a mailbox.
     */
    public boolean refreshMessageList(long accountId, long mailboxId) {
        return refreshMessageList(accountId, mailboxId, false);
    }

    /**
     * "load more messages" in a mailbox.
     */
    public boolean loadMoreMessages(long accountId, long mailboxId) {
        return refreshMessageList(accountId, mailboxId, true);
    }

    private boolean refreshMessageList(long accountId, long mailboxId, boolean loadMoreMessages) {
        final Status status = mMessageListStatus.get(mailboxId);
        if (!status.canRefresh()) return false;

        Log.i(Email.LOG_TAG, "refreshMessageList " + accountId + ", " + mailboxId + ", "
                + loadMoreMessages);
        status.onRefreshRequested();
        notifyRefreshStatusChanged(accountId, mailboxId);
        mController.updateMailbox(accountId, mailboxId);
        return true;
    }

    /**
     * Send pending messages.
     */
    public boolean sendPendingMessages(long accountId) {
        final Status status = mOutboxStatus.get(accountId);
        if (!status.canRefresh()) return false;

        Log.i(Email.LOG_TAG, "sendPendingMessages " + accountId);
        status.onRefreshRequested();
        notifyRefreshStatusChanged(accountId, -1);
        mController.sendPendingMessages(accountId);
        return true;
    }

    /**
     * Call {@link #sendPendingMessages} for all accounts.
     */
    public void sendPendingMessagesForAllAccounts() {
        Log.i(Email.LOG_TAG, "sendPendingMessagesForAllAccounts");
        Utility.runAsync(new Runnable() {
            public void run() {
                sendPendingMessagesForAllAccountsSync();
            }
        });
    }

    /**
     * Synced internal method for {@link #sendPendingMessagesForAllAccounts} for testing.
     */
    /* package */ void sendPendingMessagesForAllAccountsSync() {
        Cursor c = mContext.getContentResolver().query(EmailContent.Account.CONTENT_URI,
                EmailContent.Account.ID_PROJECTION, null, null, null);
        try {
            while (c.moveToNext()) {
                sendPendingMessages(c.getLong(EmailContent.Account.ID_PROJECTION_COLUMN));
            }
        } finally {
            c.close();
        }
    }

    public boolean isMailboxListRefreshing(long accountId) {
        return mMailboxListStatus.get(accountId).isRefreshing();
    }

    public boolean isMessageListRefreshing(long mailboxId) {
        return mMessageListStatus.get(mailboxId).isRefreshing();
    }

    public boolean isSendingMessage(long accountId) {
        return mOutboxStatus.get(accountId).isRefreshing();
    }

    public boolean isRefreshingAnyMailboxList() {
        return mMailboxListStatus.isRefreshingAny();
    }

    public boolean isRefreshingAnyMessageList() {
        return mMessageListStatus.isRefreshingAny();
    }

    public boolean isSendingAnyMessage() {
        return mOutboxStatus.isRefreshingAny();
    }

    public boolean isRefreshingOrSendingAny() {
        return isRefreshingAnyMailboxList() || isRefreshingAnyMessageList()
                || isSendingAnyMessage();
    }

    public String getErrorMessage() {
        return mErrorMessage;
    }

    private void notifyRefreshStatusChanged(long accountId, long mailboxId) {
        for (Listener l : mListeners) {
            l.onRefreshStatusChanged(accountId, mailboxId);
        }
    }

    private void reportError(long accountId, long mailboxId, String errorMessage) {
        mErrorMessage = errorMessage;
        for (Listener l : mListeners) {
            l.onMessagingError(accountId, mailboxId, mErrorMessage);
        }
    }

    /* package */ Collection<Listener> getListenersForTest() {
        return mListeners;
    }

    /* package */ Status getMailboxListStatusForTest(long accountId) {
        return mMailboxListStatus.get(accountId);
    }

    /* package */ Status getMessageListStatusForTest(long mailboxId) {
        return mMessageListStatus.get(mailboxId);
    }

    /* package */ Status getOutboxStatusForTest(long acountId) {
        return mOutboxStatus.get(acountId);
    }

    private class ControllerResult extends Controller.Result {
        private boolean mSendMailExceptionReported = false;

        private String exceptionToString(MessagingException exception) {
            if (exception == null) {
                return "(no exception)";
            } else {
                return exception.getUiErrorMessage(mContext);
            }
        }

        /**
         * Callback for mailbox list refresh.
         */
        @Override
        public void updateMailboxListCallback(MessagingException exception, long accountId,
                int progress) {
            if (Email.DEBUG && DEBUG_CALLBACK_LOG) {
                Log.d(Email.LOG_TAG, "updateMailboxListCallback " + accountId + ", " + progress
                        + ", " + exceptionToString(exception));
            }
            mMailboxListStatus.get(accountId).onCallback(exception, progress, mClock);
            if (exception != null) {
                reportError(accountId, -1, exception.getUiErrorMessage(mContext));
            }
            notifyRefreshStatusChanged(accountId, -1);
        }

        /**
         * Callback for explicit (user-driven) mailbox refresh.
         */
        @Override
        public void updateMailboxCallback(MessagingException exception, long accountId,
                long mailboxId, int progress, int dontUseNumNewMessages) {
            if (Email.DEBUG && DEBUG_CALLBACK_LOG) {
                Log.d(Email.LOG_TAG, "updateMailboxCallback " + accountId + ", "
                        + mailboxId + ", " + progress + ", " + exceptionToString(exception));
            }
            updateMailboxCallbackInternal(exception, accountId, mailboxId, progress, 0);
        }

        /**
         * Callback for implicit (timer-based) mailbox refresh.
         *
         * Do the same as {@link #updateMailboxCallback}.
         * TODO: Figure out if it's really okay to do the same as updateMailboxCallback.
         * If both the explicit refresh and the implicit refresh can run at the same time,
         * we need to keep track of their status separately.
         */
        @Override
        public void serviceCheckMailCallback(
                MessagingException exception, long accountId, long mailboxId, int progress,
                long tag) {
            if (Email.DEBUG && DEBUG_CALLBACK_LOG) {
                Log.d(Email.LOG_TAG, "serviceCheckMailCallback " + accountId + ", "
                        + mailboxId + ", " + progress + ", " + exceptionToString(exception));
            }
            updateMailboxCallbackInternal(exception, accountId, mailboxId, progress, 0);
        }

        private void updateMailboxCallbackInternal(MessagingException exception, long accountId,
                long mailboxId, int progress, int dontUseNumNewMessages) {
            // Don't use dontUseNumNewMessages.  serviceCheckMailCallback() don't set it.
            mMessageListStatus.get(mailboxId).onCallback(exception, progress, mClock);
            if (exception != null) {
                reportError(accountId, mailboxId, exception.getUiErrorMessage(mContext));
            }
            notifyRefreshStatusChanged(accountId, mailboxId);
        }


        /**
         * Send message progress callback.
         *
         * This callback is overly overloaded:
         *
         * First, we get this.
         *  result == null, messageId == -1, progress == 0:     start batch send
         *
         * Then we get these callbacks per message.
         * (Exchange backend may skip "start sending one message".)
         *  result == null, messageId == xx, progress == 0:     start sending one message
         *  result == xxxx, messageId == xx, progress == 0;     failed sending one message
         *
         * Finally we get this.
         *  result == null, messageId == -1, progres == 100;    finish sending batch
         *
         * So, let's just report the first exception we get, and ignore the rest.
         */
        @Override
        public void sendMailCallback(MessagingException exception, long accountId, long messageId,
                int progress) {
            if (Email.DEBUG && DEBUG_CALLBACK_LOG) {
                Log.d(Email.LOG_TAG, "sendMailCallback " + accountId + ", "
                        + messageId + ", " + progress + ", " + exceptionToString(exception));
            }
            if (progress == 0 && messageId == -1) {
                mSendMailExceptionReported = false;
            }
            if (messageId == -1) {
                // Update the status only for the batch start/end.
                // (i.e. don't report for each message.)
                mOutboxStatus.get(accountId).onCallback(exception, progress, mClock);
                notifyRefreshStatusChanged(accountId, -1);
            }
            if (exception != null && !mSendMailExceptionReported) {
                // Only the first error in a batch will be reported.
                mSendMailExceptionReported = true;
                reportError(accountId, messageId, exception.getUiErrorMessage(mContext));
            }
        }
    }
}
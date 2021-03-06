/*
 * Copyright (C) 2015 The Nevolution Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oasisfeng.nevo;

import android.app.Notification;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.oasisfeng.android.service.notification.StatusBarNotificationCompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Ease the code across Nevolution with following features:
 * <ul>
 *   <li>Automatic proxy for heavy notification instance as lazy binder.</li>
 *   <li>Decorator to alter package, tag, ID, key and group key.</li>
 * </ul>
 *
 * Two states: wrapper and proxy (via binder)
 *
 * Created by Oasis on 2015/1/18.
 */
public class StatusBarNotificationEvo extends StatusBarNotificationCompat {

	public static StatusBarNotificationEvo from(final StatusBarNotification sbn) {
		if (sbn instanceof StatusBarNotificationEvo) return (StatusBarNotificationEvo) sbn;
		return new StatusBarNotificationEvo(sbn.getPackageName(), null, sbn.getId(), sbn.getTag(),
				getUid(sbn), 0, 0, sbn.getNotification(), getUser(sbn), sbn.getPostTime());
	}

	public StatusBarNotificationEvo(final String pkg, final String opPkg, final int id, final String tag, final int uid, final int initialPid, final int score, final Notification notification, final UserHandle user, final long postTime) {
		super(pkg, opPkg, id, tag, uid, initialPid, score, notification, user, postTime);
		holder = new NotificationHolder(notification);
	}

	public StatusBarNotificationEvo setTag(final @Nullable String tag) {
		if (equal(tag, this.tag)) return this;
		if (equal(tag, super.getTag())) {
			this.tag = null; tag_decorated = false;
		} else {
			this.tag = tag; tag_decorated = true;
		}
		return this;
	}

	public StatusBarNotificationEvo setId(final int id) {
		if (this.id != null && id == this.id) return this;
		if (id == super.getId()) this.id = null;
		else this.id = id;
		return this;
	}

    @Override public String getTag() { return tag_decorated ? tag : super.getTag(); }
    @Override public int getId() { return id != null ? id : super.getId(); }

	/**
	 * Beware, calling this method on remote instance will retrieve the whole instance, which is inefficient and slow.
	 * This local instance will also be marked as "dirty", increasing the cost of future {@link #writeToParcel(Parcel, int)}.
	 *
	 * @deprecated Consider using {@link #notification()} whenever possible to avoid the overhead of this method.
	 */
	@Deprecated @Override public Notification getNotification() {
		try {
			if (holder == null) return super.getNotification();	// holder is null only if called by super constructor StatusBarNotification().
			if (holder instanceof INotification.Stub) return holder.get();	// Direct fetch for local instance
			if (notification == null) {
				notification = holder.get();
				NotificationCompat.getExtras(notification).setClassLoader(StatusBarNotificationEvo.class.getClassLoader());	// For our parcelable classes
			}
		} catch (final RemoteException e) { throw new IllegalStateException(e); }
		return notification;
	}

	/** Get the interface of notification for modification, to avoid the overhead of get the whole notification */
	public INotification notification() {
		return holder;
	}

	@Override public boolean isOngoing() {
		try {
			return (notification().getFlags() & Notification.FLAG_ONGOING_EVENT) != 0;
		} catch (final RemoteException e) { throw new IllegalStateException(e); }
	}

	@Override public boolean isClearable() {
		try {
			final int flags = notification().getFlags();
			return (flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR)) == 0;
		} catch (final RemoteException e) { throw new IllegalStateException(e); }
	}

	/** Write all fields except the Notification which is passed as IPC holder */
	@Override public void writeToParcel(final Parcel out, final int flags) {
		if ((flags & FLAG_WRITE_AS_ORIGINAL) != 0) {
			super.writeToParcel(out, flags & ~ FLAG_WRITE_AS_ORIGINAL);
			return;
		}
		out.writeInt(PARCEL_MAGIC);
		out.writeString(getPackageName());
		out.writeInt(getId());
		if (getTag() != null) {
			out.writeInt(1);
			out.writeString(getTag());
		} else out.writeInt(0);
		out.writeInt(getUid(this));
		getUser().writeToParcel(out, flags);
		out.writeLong(getPostTime());
		out.writeStrongInterface(notification == null ? holder : new NotificationHolder(notification));	// The local copy of notification is "dirty" (possibly modified), hence needs to be updated.
	}

	/** Write to parcel as plain {@link StatusBarNotification}, all decorations will be ignored. */
	public static final int FLAG_WRITE_AS_ORIGINAL = 0x1000;

    // Parcel written by plain StatusBarNotification
	private StatusBarNotificationEvo(final Parcel in, final @Nullable INotification holder) {
		super(in);
		//noinspection deprecation
		this.holder = holder != null ? holder : new NotificationHolder(getNotification());
	}

	private StatusBarNotificationEvo(final Parcel in) {
		super(in.readString(), null, in.readInt(), in.readInt() != 0 ? in.readString() : null, in.readInt(), 0, 0,
				NULL_NOTIFICATION, UserHandle.readFromParcel(in), in.readLong());
		holder = INotification.Stub.asInterface(in.readStrongBinder());
	}

	public static final Parcelable.Creator<StatusBarNotificationEvo> CREATOR = new Parcelable.Creator<StatusBarNotificationEvo>() {

		@Override public StatusBarNotificationEvo createFromParcel(final Parcel source) {
			final int pos = source.dataPosition();
			final int magic = source.readInt();
			//noinspection deprecation
			if (magic == PARCEL_MAGIC) return new StatusBarNotificationEvo(source);
			// Then it should be an instance of StatusBarNotification, rewind and un-parcel in that way.
			source.setDataPosition(pos);
			return new StatusBarNotificationEvo(source, null);
		}

		public StatusBarNotificationEvo[] newArray(final int size) { return new StatusBarNotificationEvo[size]; }
	};

	private static int getUid(final StatusBarNotification sbn) {
		if (sMethodGetUid != null)
			try { return (int) sMethodGetUid.invoke(sbn); } catch (final Exception ignored) {}
		if (sFieldUid != null)
			try { return (int) sFieldUid.get(sbn); } catch (final IllegalAccessException ignored) {}
		// TODO: PackageManager.getPackageUid()
		Log.e(TAG, "Incompatible ROM: StatusBarNotification");
		return 0;
	}

	private static boolean equal(final @Nullable Object a, final @Nullable Object b) {
		return a == b || (a != null && a.equals(b));
	}

    private String tag;     // Null is allowed, that's why "tag_decorated" is introduced.
    private Integer id;
    private boolean tag_decorated;
    private final INotification holder;
	private @Nullable Notification notification;	// Cache of remote notification to avoid expensive duplicate fetch.

    private static final int PARCEL_MAGIC = "NEVO".hashCode();  // TODO: Are they really magic enough?
	private static final Method sMethodGetUid;
	private static final Field sFieldUid;
	static {
		Method method = null; Field field = null;
		try {
			method = StatusBarNotification.class.getDeclaredMethod("getUid");
			method.setAccessible(true);
		} catch (final NoSuchMethodException ignored) {}
		sMethodGetUid = method;
		if (method == null) try {       // If no such method, try accessing the field
			field = StatusBarNotification.class.getDeclaredField("uid");
			field.setAccessible(true);
		} catch (final NoSuchFieldException ignored) {}
		sFieldUid = field;
	}

	private static final Notification NULL_NOTIFICATION = new Notification();	// Must be placed before VOID to avoid NPE.
	private static final String TAG = "Nevo.SBN";
}

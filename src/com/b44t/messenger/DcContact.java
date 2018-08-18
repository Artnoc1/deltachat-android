/*******************************************************************************
 *
 *                              Delta Chat Android
 *                           (C) 2017 Björn Petersen
 *                    Contact: r10s@b44t.com, http://b44t.com
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see http://www.gnu.org/licenses/ .
 *
 ******************************************************************************/


package com.b44t.messenger;


public class DcContact {

    public final static int DC_CONTACT_ID_SELF = 1;
    public final static int DC_CONTACT_ID_DEVICE = 2;
    public final static int DC_CONTACT_ID_LAST_SPECIAL = 9;

    public DcContact(long hContact) {
        m_hContact = hContact;
    }

    @Override protected void finalize() throws Throwable {
        super.finalize();
        DcContactUnref(m_hContact);
        m_hContact = 0;
    }

    public native String getName();
    public native String getDisplayName();
    public native String getFirstName();
    public native String getAddr();
    public native String getNameNAddr();
    public native boolean isBlocked();
    public native boolean isVerified();

    // working with raw c-data
    private long m_hContact; // must not be renamed as referenced by JNI
    private native static void DcContactUnref(long hContact);
}
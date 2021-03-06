/*
 *
 * This file is part of the XiPKI project.
 * Copyright (c) 2014 - 2015 Lijun Liao
 * Author: Lijun Liao
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License version 3
 * as published by the Free Software Foundation with the addition of the
 * following permission added to Section 15 as permitted in Section 7(a):
 * FOR ANY PART OF THE COVERED WORK IN WHICH THE COPYRIGHT IS OWNED BY
 * THE AUTHOR LIJUN LIAO. LIJUN LIAO DISCLAIMS THE WARRANTY OF NON INFRINGEMENT
 * OF THIRD PARTY RIGHTS.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * The interactive user interfaces in modified source and object code versions
 * of this program must display Appropriate Legal Notices, as required under
 * Section 5 of the GNU Affero General Public License.
 *
 * You can be released from the requirements of the license by purchasing
 * a commercial license. Buying such a license is mandatory as soon as you
 * develop commercial activities involving the XiPKI software without
 * disclosing the source code of your own applications.
 *
 * For more information, please contact Lijun Liao at this
 * address: lijun.liao@gmail.com
 */

package org.xipki.ca.server.mgmt.api;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.xipki.common.CmpUtf8Pairs;
import org.xipki.common.ConfigurationException;
import org.xipki.common.ParamChecker;
import org.xipki.common.util.CollectionUtil;
import org.xipki.common.util.StringUtil;

/**
 *
 * Example configuration
 * updateMode?<'interval'|'onDemand'>
 *
 * # For all updateMode
 *
 * # Whether expired certificates are considered. Default is false
 * expiredCerts.included?<'true'|'false'>
 *
 * # Whether certificates are embedded in CRL, XiPKI-customized extension. Default is false
 * certs.embedded = <'true'|'false'>
 *
 * # List of OIDs of extensions to be embedded in CRL,
 * # Unspecified or empty extensions indicates that the CA decides.
 * extensions?<comma delimited OIDs of extensions>
 *
 * # The following settings are only for updateMode 'interval'
 *
 * # Number of intervals to generate a full CRL. Default is 1
 * # Should be greater than 0
 * fullCRL.intervals?<integer>
 *
 * # should be 0 or not greater than baseCRL.intervals. Default is 0.
 * # 0 indicates that no deltaCRL will be generated
 * deltaCRL.intervals=<integer>
 *
 * overlap.minutes?<minutes of overlap>
 *
 * # should be less than fullCRL.intervals.
 * # If activated, a deltaCRL will be generated only between two full CRLs
 * deltaCRL.intervals?<integer>
 *
 * # Exactly one of interval.minutes and interval.days should be specified
 * # Number of minutes of one interval. At least 60 minutes
 * interval.minutes=<minutes of one interval>
 *
 * # UTC time of generation of CRL, one interval covers 1 day.
 * interval.time?<updatet time (hh:mm of UTC time)>
 *
 * # Whether the nextUpdate of a fullCRL is the update time of the fullCRL
 * # Default is false
 * fullCRL.extendedNextUpdate?<'true'|'false'>
 *
 * # Whether only user certificates are considered in CRL
 * onlyContainsUserCerts?<'true'|'false'>
 *
 * # Whether only CA certificates are considered in CRL
 * onlyContainsCACerts?<'true'|'false'>
 *
 * @author Lijun Liao
 */

public class CRLControl implements Serializable
{
    private static final long serialVersionUID = 1L;

    public static final String KEY_updateMode = "updateMode";
    public static final String KEY_extensions = "extensions";
    public static final String KEY_expiredCerts_included = "expiredCerts.included";
    public static final String KEY_certs_embedded = "certs.embedded";
    public static final String KEY_fullCRL_intervals = "fullCRL.intervals";
    public static final String KEY_deltaCRL_intervals = "deltaCRL.intervals";
    public static final String KEY_overlap_minutes = "overlap.minutes";
    public static final String KEY_interval_minutes = "interval.minutes";
    public static final String KEY_interval_time = "interval.time";
    public static final String KEY_fullCRL_extendedNextUpdate = "fullCRL.extendedNextUpdate";
    public static final String KEY_onlyContainsUserCerts = "onlyContainsUserCerts";
    public static final String KEY_onlyContainsCACerts = "onlyContainsCACerts";

    public static enum UpdateMode implements Serializable
    {
        interval,
        onDemand;

        public static UpdateMode getUpdateMode(
                final String mode)
        {
            for(UpdateMode v : values())
            {
                if(v.name().equalsIgnoreCase(mode))
                {
                    return v;
                }
            }

            return null;
        }
    }

    public static class HourMinute
    {
        private final int hour;
        private final int minute;

        public HourMinute(
                final int hour,
                final int minute)
        throws IllegalArgumentException
        {
            if(hour < 0 | hour > 23)
            {
                throw new IllegalArgumentException("invalid hour " + hour);
            }

            if(minute < 0 | minute > 59)
            {
                throw new IllegalArgumentException("invalid minute " + minute);
            }

            this.hour = hour;
            this.minute = minute;
        }

        public int getHour()
        {
            return hour;
        }

        public int getMinute()
        {
            return minute;
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            if(hour < 10)
            {
                sb.append("0");
            }
            sb.append(hour);
            sb.append(":");
            if(minute < 10)
            {
                sb.append("0");
            }
            sb.append(minute);
            return sb.toString();
        }

        @Override
        public boolean equals(Object obj)
        {
            if(obj instanceof HourMinute == false)
            {
                return false;
            }

            HourMinute b = (HourMinute) obj;
            return hour == b.hour && minute == b.minute;
        }
    }

    private UpdateMode updateMode = UpdateMode.interval;
    private boolean embedsCerts = false;
    private boolean includeExpiredCerts = false;
    private int fullCRLIntervals = 1;
    private int deltaCRLIntervals = 0;
    private int overlapMinutes = 10;
    private boolean extendedNextUpdate = false;
    private Integer intervalMinutes;
    private HourMinute intervalDayTime;
    private boolean onlyContainsUserCerts = false;
    private boolean onlyContainsCACerts = false;

    private final Set<String> extensionOIDs;

    public CRLControl(
            final String conf)
    throws ConfigurationException
    {
        ParamChecker.assertNotBlank("conf", conf);
        CmpUtf8Pairs props;
        try
        {
            props = new CmpUtf8Pairs(conf);
        }catch(RuntimeException e)
        {
            throw new ConfigurationException(e.getClass().getName() + ": " + e.getMessage(), e);
        }

        String s = props.getValue(KEY_updateMode);
        if(s == null)
        {
            this.updateMode = UpdateMode.interval;
        }
        else
        {
            this.updateMode = UpdateMode.getUpdateMode(s);
            if(this.updateMode == null)
            {
                throw new ConfigurationException("invalid " + KEY_updateMode + ": " + s);
            }
        }

        this.includeExpiredCerts = getBoolean(props, KEY_expiredCerts_included, false);
        this.embedsCerts = getBoolean(props, KEY_certs_embedded, false);

        s = props.getValue(KEY_extensions);
        if(s == null)
        {
            this.extensionOIDs = Collections.emptySet();
        }
        else
        {
            Set<String> extensionOIDs = StringUtil.splitAsSet(s, ", ");
            // check the OID
            for(String extensionOID : extensionOIDs)
            {
                try
                {
                    new ASN1ObjectIdentifier(extensionOID);
                }catch(IllegalArgumentException e)
                {
                    throw new ConfigurationException(extensionOID + " is not a valid OID");
                }
            }
            this.extensionOIDs = extensionOIDs;
        }

        this.onlyContainsCACerts = getBoolean(props, KEY_onlyContainsCACerts, false);
        this.onlyContainsUserCerts = getBoolean(props, KEY_onlyContainsUserCerts, false);

        if(this.updateMode != UpdateMode.onDemand)
        {
            this.fullCRLIntervals = getInteger(props, KEY_fullCRL_intervals, 1);
            this.deltaCRLIntervals = getInteger(props, KEY_deltaCRL_intervals, 0);
            this.extendedNextUpdate = getBoolean(props, KEY_fullCRL_extendedNextUpdate, false);
            this.overlapMinutes = getInteger(props, KEY_overlap_minutes, 60);
            s = props.getValue(KEY_interval_time);
            if(s != null)
            {
                List<String> tokens = StringUtil.split(s.trim(), ":");
                if(tokens.size() != 2)
                {
                    throw new ConfigurationException("invalid " + KEY_interval_time + ": '" + s + "'");
                }

                try
                {
                    int hour = Integer.parseInt(tokens.get(0));
                    int minute = Integer.parseInt(tokens.get(1));
                    this.intervalDayTime = new HourMinute(hour, minute);
                }catch(IllegalArgumentException e)
                {
                    throw new ConfigurationException("invalid " + KEY_interval_time + ": '" + s + "'");
                }
            }
            else
            {
                int minutes = getInteger(props, KEY_interval_minutes, 0);
                if(minutes < this.overlapMinutes + 30)
                {
                    throw new ConfigurationException("invalid " + KEY_interval_minutes + ": '" + minutes +
                            " is less than than 30 + " + this.overlapMinutes);
                }
                this.intervalMinutes = minutes;
            }
        }

        validate();
    }

    private static int getInteger(
            final CmpUtf8Pairs props,
            final String propKey,
            final int dfltValue)
    throws ConfigurationException
    {
        String s = props.getValue(propKey);
        if(s != null)
        {
            try
            {
                return Integer.parseInt(s.trim());
            }catch(NumberFormatException e)
            {
                throw new ConfigurationException(propKey + " does not have numeric value: " + s);
            }
        }
        return dfltValue;
    }

    private static boolean getBoolean(
            final CmpUtf8Pairs props,
            final String propKey,
            final boolean dfltValue)
    throws ConfigurationException
    {
        String s = props.getValue(propKey);
        if(s != null)
        {
            s = s.trim();
            if("true".equalsIgnoreCase(s))
            {
                return Boolean.TRUE;
            }
            else if("false".equalsIgnoreCase(s))
            {
                return Boolean.FALSE;
            }
            else
            {
                throw new ConfigurationException(propKey + " does not have boolean value: " + s);
            }
        }
        return dfltValue;
    }

    public String getConf()
    {
        CmpUtf8Pairs pairs = new CmpUtf8Pairs();
        pairs.putUtf8Pair(KEY_updateMode, updateMode.name());
        pairs.putUtf8Pair(KEY_expiredCerts_included, Boolean.toString(includeExpiredCerts));
        pairs.putUtf8Pair(KEY_certs_embedded, Boolean.toString(embedsCerts));
        pairs.putUtf8Pair(KEY_onlyContainsCACerts, Boolean.toString(onlyContainsCACerts));
        pairs.putUtf8Pair(KEY_onlyContainsUserCerts, Boolean.toString(onlyContainsUserCerts));
        if(updateMode != UpdateMode.onDemand)
        {
            pairs.putUtf8Pair(KEY_fullCRL_intervals, Integer.toString(fullCRLIntervals));
            pairs.putUtf8Pair(KEY_fullCRL_extendedNextUpdate, Boolean.toString(extendedNextUpdate));
            pairs.putUtf8Pair(KEY_deltaCRL_intervals, Integer.toString(deltaCRLIntervals));

            if(intervalDayTime != null)
            {
                pairs.putUtf8Pair(KEY_interval_time, intervalDayTime.toString());
            }

            if(intervalMinutes != null)
            {
                pairs.putUtf8Pair(KEY_interval_minutes, intervalMinutes.toString());
            }
        }

        if(CollectionUtil.isNotEmpty(extensionOIDs))
        {
            StringBuilder extensionsSb = new StringBuilder();
            for(String oid : extensionOIDs)
            {
                extensionsSb.append(oid).append(",");
            }
            extensionsSb.deleteCharAt(extensionsSb.length() - 1);
            pairs.putUtf8Pair(KEY_extensions, extensionsSb.toString());
        }

        return pairs.getEncoded();
    }

    @Override
    public String toString()
    {
        return getConf();
    }

    public UpdateMode getUpdateMode()
    {
        return updateMode;
    }

    public boolean isEmbedsCerts()
    {
        return embedsCerts;
    }

    public boolean isIncludeExpiredCerts()
    {
        return includeExpiredCerts;
    }

    public int getFullCRLIntervals()
    {
        return fullCRLIntervals;
    }

    public int getDeltaCRLIntervals()
    {
        return deltaCRLIntervals;
    }

    public int getOverlapMinutes()
    {
        return overlapMinutes;
    }

    public Integer getIntervalMinutes()
    {
        return intervalMinutes;
    }

    public HourMinute getIntervalDayTime()
    {
        return intervalDayTime;
    }

    public Set<String> getExtensionOIDs()
    {
        return extensionOIDs;
    }

    public boolean isExtendedNextUpdate()
    {
        return extendedNextUpdate;
    }

    public boolean isOnlyContainsUserCerts()
    {
        return onlyContainsUserCerts;
    }

    public boolean isOnlyContainsCACerts()
    {
        return onlyContainsCACerts;
    }

    public void validate()
    throws ConfigurationException
    {
        if(onlyContainsCACerts && onlyContainsUserCerts)
        {
            throw new ConfigurationException("onlyContainsCACerts and onlyContainsUserCerts can not be both true");
        }

        if(updateMode == UpdateMode.onDemand)
        {
            return;
        }

        if(fullCRLIntervals < deltaCRLIntervals)
        {
            throw new ConfigurationException("fullCRLIntervals could not be less than deltaCRLIntervals " +
                    fullCRLIntervals + " < " + deltaCRLIntervals);
        }

        if(fullCRLIntervals < 1)
        {
            throw new ConfigurationException("fullCRLIntervals could not be less than 1: " + fullCRLIntervals);
        }

        if(deltaCRLIntervals < 0)
        {
            throw new ConfigurationException("deltaCRLIntervals could not be less than 0: " + deltaCRLIntervals);
        }
    }

    @Override
    public boolean equals(Object obj)
    {
        if(obj instanceof CRLControl == false)
        {
            return false;
        }

        CRLControl b = (CRLControl) obj;
        if(deltaCRLIntervals != b.deltaCRLIntervals ||
                embedsCerts != b.embedsCerts ||
                extendedNextUpdate != b.extendedNextUpdate ||
                fullCRLIntervals != b.fullCRLIntervals ||
                includeExpiredCerts != b.includeExpiredCerts ||
                onlyContainsCACerts != b.onlyContainsCACerts ||
                onlyContainsUserCerts != b.onlyContainsUserCerts)
        {
            return false;
        }

        if(extensionOIDs == null)
        {
            if(b.extensionOIDs != null)
            {
                return false;
            }
        }
        else if(extensionOIDs.equals(b.extensionOIDs) == false)
        {
            return false;
        }

        if(intervalMinutes == null)
        {
            if(b.intervalMinutes != null)
            {
                return false;
            }
        }
        else if(intervalMinutes.equals(b.intervalMinutes) == false)
        {
            return false;
        }

        if(intervalDayTime == null)
        {
            if(b.intervalDayTime != null)
            {
                return false;
            }
        }
        else if(intervalDayTime.equals(b.intervalDayTime) == false)
        {
            return false;
        }

        if(updateMode == null)
        {
            if(b.updateMode != null)
            {
                return false;
            }
        }
        else if(updateMode.equals(b.updateMode) == false)
        {
            return false;
        }

        return true;
    }
}

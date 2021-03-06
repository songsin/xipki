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

package org.xipki.ca.server.impl;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.bouncycastle.util.encoders.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.ca.api.OperationException;
import org.xipki.ca.api.X509CertWithDBCertId;
import org.xipki.ca.api.profile.CertValidity;
import org.xipki.ca.server.impl.store.CertificateStore;
import org.xipki.ca.server.mgmt.api.AddUserEntry;
import org.xipki.ca.server.mgmt.api.CAEntry;
import org.xipki.ca.server.mgmt.api.CAHasRequestorEntry;
import org.xipki.ca.server.mgmt.api.CAManager;
import org.xipki.ca.server.mgmt.api.CAMgmtException;
import org.xipki.ca.server.mgmt.api.CAStatus;
import org.xipki.ca.server.mgmt.api.CertArt;
import org.xipki.ca.server.mgmt.api.CertprofileEntry;
import org.xipki.ca.server.mgmt.api.ChangeCAEntry;
import org.xipki.ca.server.mgmt.api.CmpControl;
import org.xipki.ca.server.mgmt.api.CmpControlEntry;
import org.xipki.ca.server.mgmt.api.CmpRequestorEntry;
import org.xipki.ca.server.mgmt.api.CmpResponderEntry;
import org.xipki.ca.server.mgmt.api.DuplicationMode;
import org.xipki.ca.server.mgmt.api.Permission;
import org.xipki.ca.server.mgmt.api.PublisherEntry;
import org.xipki.ca.server.mgmt.api.ScepEntry;
import org.xipki.ca.server.mgmt.api.UserEntry;
import org.xipki.ca.server.mgmt.api.ValidityMode;
import org.xipki.ca.server.mgmt.api.X509CAEntry;
import org.xipki.ca.server.mgmt.api.X509ChangeCAEntry;
import org.xipki.ca.server.mgmt.api.X509CrlSignerEntry;
import org.xipki.common.CertRevocationInfo;
import org.xipki.common.ConfigurationException;
import org.xipki.common.ParamChecker;
import org.xipki.common.util.PasswordHash;
import org.xipki.common.util.SecurityUtil;
import org.xipki.common.util.StringUtil;
import org.xipki.common.util.X509Util;
import org.xipki.datasource.api.DataSourceWrapper;
import org.xipki.datasource.api.exception.DataAccessException;
import org.xipki.datasource.api.exception.DuplicateKeyException;
import org.xipki.security.api.SecurityFactory;
import org.xipki.security.api.SignerException;

/**
 * @author Lijun Liao
 */

class CAManagerQueryExecutor
{

    private static final Logger LOG = LoggerFactory.getLogger(CAManagerQueryExecutor.class);
    private Random random;

    private DataSourceWrapper dataSource;

    CAManagerQueryExecutor(
            final DataSourceWrapper dataSource)
    {
        ParamChecker.assertNotNull("dataSource", dataSource);
        this.dataSource = dataSource;
        this.random = new Random();
    }

    private X509Certificate generateCert(
            final String b64Cert)
    throws CAMgmtException
    {
        if(b64Cert == null)
        {
            return null;
        }

        byte[] encodedCert = Base64.decode(b64Cert);
        try
        {
            return X509Util.parseCert(encodedCert);
        } catch (CertificateException | IOException e)
        {
            throw new CAMgmtException(e.getMessage(), e);
        }
    }

    private Statement createStatement()
    throws CAMgmtException
    {
        Connection dsConnection;
        try
        {
            dsConnection = dataSource.getConnection();
        } catch (DataAccessException e)
        {
            throw new CAMgmtException("could not get connection", e);
        }

        try
        {
            return dataSource.createStatement(dsConnection);
        }catch(DataAccessException e)
        {
            throw new CAMgmtException("could not create statement", e);
        }
    }

    private PreparedStatement prepareFetchFirstStatement(
            final String sql)
    throws CAMgmtException
    {
        return prepareStatement(dataSource.createFetchFirstSelectSQL(sql, 1));
    }

    private PreparedStatement prepareStatement(
            final String sql)
    throws CAMgmtException
    {
        Connection dsConnection;
        try
        {
            dsConnection = dataSource.getConnection();
        } catch (DataAccessException e)
        {
            throw new CAMgmtException(e.getMessage(), e);
        }

        try
        {
            return dataSource.prepareStatement(dsConnection, sql);
        }catch(DataAccessException e)
        {
            throw new CAMgmtException(e.getMessage(), e);
        }
    }

    SystemEvent getSystemEvent(
            final String eventName)
    throws CAMgmtException
    {
        final String sql = "SELECT EVENT_TIME, EVENT_OWNER FROM SYSTEM_EVENT WHERE NAME=?";
        PreparedStatement ps = null;
        ResultSet rs = null;

        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, eventName);
            rs = ps.executeQuery();

            if(rs.next())
            {
                long eventTime = rs.getLong("EVENT_TIME");
                String eventOwner = rs.getString("EVENT_OWNER");
                return new SystemEvent(eventName, eventOwner, eventTime);
            } else
            {
                return null;
            }
        } catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        } finally
        {
            dataSource.releaseResources(ps, rs);
        }
    }

    void deleteSystemEvent(
            final String eventName)
    throws CAMgmtException
    {
        final String sql = "DELETE FROM SYSTEM_EVENT WHERE NAME=?";
        PreparedStatement ps = null;

        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, eventName);
            ps.executeUpdate();
        } catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        } finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void addSystemEvent(
            final SystemEvent systemEvent)
    throws CAMgmtException
    {
        final String sql = "INSERT INTO SYSTEM_EVENT (NAME, EVENT_TIME, EVENT_TIME2, EVENT_OWNER)"
                + " VALUES (?, ?, ?, ?)";

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            int idx = 1;
            ps.setString(idx++, systemEvent.getName());
            ps.setLong(idx++, systemEvent.getEventTime());
            ps.setTimestamp(idx++, new Timestamp(systemEvent.getEventTime() * 1000L));
            ps.setString(idx++, systemEvent.getOwner());
        } catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean changeSystemEvent(
            final SystemEvent systemEvent)
    throws CAMgmtException
    {
        deleteSystemEvent(systemEvent.getName());
        addSystemEvent(systemEvent);
        return true;
    }

    Map<String, String> createEnvParameters()
    throws CAMgmtException
    {
        Map<String, String> map = new HashMap<>();
        final String sql = "SELECT NAME, VALUE2 FROM ENVIRONMENT";
        Statement stmt = null;
        ResultSet rs = null;

        try
        {
            stmt = createStatement();
            rs = stmt.executeQuery(sql);

            while(rs.next())
            {
                String name = rs.getString("NAME");
                String value = rs.getString("VALUE2");
                map.put(name, value);
            }
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(stmt, rs);
        }

        return map;
    }

    Map<String, String> createCaAliases()
    throws CAMgmtException
    {
        Map<String, String> map = new HashMap<>();

        final String sql = "SELECT NAME, CA_NAME FROM CAALIAS";
        Statement stmt = null;
        ResultSet rs = null;

        try
        {
            stmt = createStatement();
            rs = stmt.executeQuery(sql);

            while(rs.next())
            {
                String name = rs.getString("NAME");
                String caName = rs.getString("CA_NAME");
                map.put(name, caName);
            }
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(stmt, rs);
        }

        return map;
    }

    CertprofileEntry createCertprofile(
            final String name)
    throws CAMgmtException
    {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        final String sql = "TYPE, CONF FROM PROFILE WHERE NAME=?";
        try
        {
            stmt = prepareFetchFirstStatement(sql);
            stmt.setString(1, name);
            rs = stmt.executeQuery();

            if(rs.next())
            {
                String type = rs.getString("TYPE");
                String conf = rs.getString("CONF");

                return new CertprofileEntry(name, type, conf);
            }
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        } finally
        {
            dataSource.releaseResources(stmt, rs);
        }

        return null;
    }

    List<String> getNamesFromTable(
            final String table)
    throws CAMgmtException
    {
        final String sql = new StringBuilder("SELECT NAME FROM ").append(table).toString();
        Statement stmt = null;
        ResultSet rs = null;
        try
        {
            stmt = createStatement();
            rs = stmt.executeQuery(sql);

            List<String> names = new LinkedList<>();

            while(rs.next())
            {
                String name = rs.getString("NAME");
                if(StringUtil.isNotBlank(name))
                {
                    names.add(name);
                }
            }

            return names;
        } catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }
        finally
        {
            dataSource.releaseResources(stmt, rs);
        }
    }

    PublisherEntry createPublisher(
            final String name)
    throws CAMgmtException
    {
        final String sql = "TYPE, CONF FROM PUBLISHER WHERE NAME=?";
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try
        {
            stmt = prepareFetchFirstStatement(sql);
            stmt.setString(1, name);
            rs = stmt.executeQuery();

            if(rs.next())
            {
                String type = rs.getString("TYPE");
                String conf = rs.getString("CONF");

                return new PublisherEntry(name, type, conf);
            }
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(stmt, rs);
        }

        return null;
    }

    CmpRequestorEntry createRequestor(
            final String name)
    throws CAMgmtException
    {
        final String sql = "CERT FROM REQUESTOR WHERE NAME=?";
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try
        {
            stmt = prepareFetchFirstStatement(sql);
            stmt.setString(1, name);
            rs = stmt.executeQuery();

            if(rs.next())
            {
                String b64Cert = rs.getString("CERT");
                return new CmpRequestorEntry(name, b64Cert);
            }
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(stmt, rs);
        }

        return null;
    }

    X509CrlSignerEntry createCrlSigner(
            final String name)
    throws CAMgmtException
    {
        final String sql = "SIGNER_TYPE, SIGNER_CONF, SIGNER_CERT, CRL_CONTROL FROM CRLSIGNER WHERE NAME=?";
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try
        {
            stmt = prepareFetchFirstStatement(sql);
            stmt.setString(1, name);
            rs = stmt.executeQuery();

            if(rs.next())
            {
                String signer_type = rs.getString("SIGNER_TYPE");
                String signer_conf = rs.getString("SIGNER_CONF");
                String signer_cert = rs.getString("SIGNER_CERT");
                String crlControlConf = rs.getString("CRL_CONTROL");
                return new X509CrlSignerEntry(name, signer_type, signer_conf, signer_cert, crlControlConf);
            }
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }catch(ConfigurationException e)
        {
            throw new CAMgmtException(e.getMessage(), e);
        }finally
        {
            dataSource.releaseResources(stmt, rs);
        }
        return null;
    }

    CmpControlEntry createCmpControl(
            final String name)
    throws CAMgmtException
    {
        final String sql = "CONF FROM CMPCONTROL WHERE NAME=?";
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try
        {
            stmt = prepareFetchFirstStatement(sql);
            stmt.setString(1, name);
            rs = stmt.executeQuery();

            if(rs.next() == false)
            {
                return null;
            }

            String conf = rs.getString("CONF");
            return new CmpControlEntry(name, conf);
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(stmt, rs);
        }
    }

    CmpResponderEntry createResponder(
            final String name)
    throws CAMgmtException
    {
        final String sql = "TYPE, CONF, CERT FROM RESPONDER WHERE NAME=?";
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try
        {
            stmt = prepareFetchFirstStatement(sql);
            stmt.setString(1, name);
            rs = stmt.executeQuery();

            if(rs.next() == false)
            {
                return null;
            }

            String type = rs.getString("TYPE");
            String conf = rs.getString("CONF");
            String b64Cert = rs.getString("CERT");
            return new CmpResponderEntry(name, type, conf, b64Cert);
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(stmt, rs);
        }
    }

    X509CAInfo createCAInfo(
            final String name,
            final boolean masterMode,
            final CertificateStore certstore)
    throws CAMgmtException
    {
        final String sql = "NAME, ART, NEXT_SERIAL, NEXT_CRLNO, STATUS, MAX_VALIDITY" +
                ", CERT, SIGNER_TYPE, SIGNER_CONF, CRLSIGNER_NAME, RESPONDER_NAME, CMPCONTROL_NAME" +
                ", DUPLICATE_KEY, DUPLICATE_SUBJECT, PERMISSIONS, NUM_CRLS" +
                ", EXPIRATION_PERIOD, REVOKED, REV_REASON, REV_TIME, REV_INV_TIME, VALIDITY_MODE" +
                ", CRL_URIS, DELTACRL_URIS, OCSP_URIS, CACERT_URIS, EXTRA_CONTROL" +
                " FROM CA WHERE NAME=?";
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try
        {
            stmt = prepareFetchFirstStatement(sql);
            stmt.setString(1, name);
            rs = stmt.executeQuery();

            if(rs.next())
            {
                int artCode = rs.getInt("ART");
                if(artCode != CertArt.X509PKC.getCode())
                {
                    throw new CAMgmtException("CA " + name + " is not X509CA, and is not supported");
                }

                long next_serial = rs.getLong("NEXT_SERIAL");
                int next_crlNo = rs.getInt("NEXT_CRLNO");
                String status = rs.getString("STATUS");
                String crl_uris = rs.getString("CRL_URIS");
                String delta_crl_uris = rs.getString("DELTACRL_URIS");
                String ocsp_uris = rs.getString("OCSP_URIS");
                String cacert_uris = rs.getString("CACERT_URIS");
                String max_validityS = rs.getString("MAX_VALIDITY");
                CertValidity max_validity = CertValidity.getInstance(max_validityS);
                String b64cert = rs.getString("CERT");
                String signer_type = rs.getString("SIGNER_TYPE");
                String signer_conf = rs.getString("SIGNER_CONF");
                String crlsigner_name = rs.getString("CRLSIGNER_NAME");
                String responder_name = rs.getString("RESPONDER_NAME");
                String cmpcontrol_name = rs.getString("CMPCONTROL_NAME");
                int duplicateKeyI = rs.getInt("DUPLICATE_KEY");
                int duplicateSubjectI = rs.getInt("DUPLICATE_SUBJECT");
                int numCrls = rs.getInt("NUM_CRLS");
                int expirationPeriod = rs.getInt("EXPIRATION_PERIOD");
                String extra_control = rs.getString("EXTRA_CONTROL");

                CertRevocationInfo revocationInfo = null;
                boolean revoked = rs.getBoolean("REVOKED");
                if(revoked)
                {
                    int rev_reason = rs.getInt("REV_REASON");
                    long rev_time = rs.getInt("REV_TIME");
                    long rev_invalidity_time = rs.getInt("REV_INV_TIME");
                    revocationInfo = new CertRevocationInfo(rev_reason, new Date(rev_time * 1000),
                            rev_invalidity_time == 0 ? null : new Date(rev_invalidity_time * 1000));
                }

                String s = rs.getString("PERMISSIONS");
                Set<Permission> permissions = getPermissions(s);

                List<String> lCrlUris = null;
                if(StringUtil.isNotBlank(crl_uris))
                {
                    lCrlUris = StringUtil.split(crl_uris, " \t");
                }

                List<String> lDeltaCrlUris = null;
                if(StringUtil.isNotBlank(delta_crl_uris))
                {
                    lDeltaCrlUris = StringUtil.split(delta_crl_uris, " \t");
                }

                List<String> lOcspUris = null;
                if(StringUtil.isNotBlank(ocsp_uris))
                {
                    lOcspUris = StringUtil.split(ocsp_uris, " \t");
                }

                List<String> lCacertUris = null;
                if(StringUtil.isNotBlank(cacert_uris))
                {
                    lCacertUris = StringUtil.split(cacert_uris, " \t");
                }

                X509CAEntry entry = new X509CAEntry(name, next_serial, next_crlNo, signer_type, signer_conf,
                        lCacertUris, lOcspUris, lCrlUris, lDeltaCrlUris, numCrls, expirationPeriod);
                X509Certificate cert = generateCert(b64cert);
                entry.setCertificate(cert);

                CAStatus caStatus = CAStatus.getCAStatus(status);
                if(caStatus == null)
                {
                    caStatus = CAStatus.INACTIVE;
                }
                entry.setStatus(caStatus);

                entry.setMaxValidity(max_validity);

                if(crlsigner_name != null)
                {
                    entry.setCrlSignerName(crlsigner_name);
                }

                if(responder_name != null)
                {
                    entry.setResponderName(responder_name);
                }

                if(extra_control != null)
                {
                    entry.setExtraControl(extra_control);
                }

                if(cmpcontrol_name != null)
                {
                    entry.setCmpControlName(cmpcontrol_name);
                }

                entry.setDuplicateKeyMode(DuplicationMode.getInstance(duplicateKeyI));
                entry.setDuplicateSubjectMode(DuplicationMode.getInstance(duplicateSubjectI));
                entry.setPermissions(permissions);
                entry.setRevocationInfo(revocationInfo);

                String validityModeS = rs.getString("VALIDITY_MODE");
                ValidityMode validityMode = null;
                if(validityModeS != null)
                {
                    validityMode = ValidityMode.getInstance(validityModeS);
                }
                if(validityMode == null)
                {
                    validityMode = ValidityMode.STRICT;
                }
                entry.setValidityMode(validityMode);

                try
                {
                    if(masterMode)
                    {
                        X509CertWithDBCertId cm = new X509CertWithDBCertId(entry.getCertificate());
                        certstore.addCa(cm);
                    }

                    return new X509CAInfo(entry, certstore);
                } catch (OperationException e)
                {
                    throw new CAMgmtException(e.getMessage(), e);
                }
            }
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(stmt, rs);
        }

        return null;
    }

    Set<CAHasRequestorEntry> createCAhasRequestors(
            final String caName)
    throws CAMgmtException
    {
        final String sql = "SELECT REQUESTOR_NAME, RA, PERMISSIONS, PROFILES FROM CA_HAS_REQUESTOR" +
                " WHERE CA_NAME=?";
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try
        {
            stmt = prepareStatement(sql);
            stmt.setString(1, caName);
            rs = stmt.executeQuery();

            Set<CAHasRequestorEntry> ret = new HashSet<>();
            while(rs.next())
            {
                String requestorName = rs.getString("REQUESTOR_NAME");
                boolean ra = rs.getBoolean("RA");
                String s = rs.getString("PERMISSIONS");
                Set<Permission> permissions = getPermissions(s);

                s = rs.getString("PROFILES");
                List<String> list = StringUtil.split(s, ",");
                Set<String> profiles = (list == null)? null : new HashSet<>(list);
                CAHasRequestorEntry entry = new CAHasRequestorEntry(requestorName);
                entry.setRa(ra);
                entry.setPermissions(permissions);
                entry.setProfiles(profiles);

                ret.add(entry);
            }

            return ret;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(stmt, rs);
        }
    }

    Map<String, String> createCAhasProfiles(
            final String caName)
    throws CAMgmtException
    {
        final String sql = new StringBuilder("SELECT PROFILE_NAME, PROFILE_LOCALNAME FROM CA_HAS_PROFILE")
                .append(" WHERE CA_NAME=?").toString();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try
        {
            stmt = prepareStatement(sql);
            stmt.setString(1, caName);
            rs = stmt.executeQuery();

            Map<String, String> ret = new HashMap<>();
            while(rs.next())
            {
                String profileName = rs.getString("PROFILE_NAME");
                String profileLocalname = rs.getString("PROFILE_LOCALNAME");
                ret.put(profileLocalname, profileName);
            }

            return ret;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(stmt, rs);
        }
    }

    Set<String> createCAhasPublishers(
            final String caName)
    throws CAMgmtException
    {
        return createCAhasNames(caName, "PUBLISHER_NAME", "CA_HAS_PUBLISHER");
    }

    Set<String> createCAhasNames(
            final String caName,
            final String columnName,
            final String table)
    throws CAMgmtException
    {
        final String sql = new StringBuilder("SELECT ").append(columnName).append(" FROM ")
                .append(table).append(" WHERE CA_NAME=?").toString();
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try
        {
            stmt = prepareStatement(sql);
            stmt.setString(1, caName);
            rs = stmt.executeQuery();

            Set<String> ret = new HashSet<>();
            while(rs.next())
            {
                String name = rs.getString(columnName);
                ret.add(name);
            }

            return ret;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(stmt, rs);
        }
    }

    boolean deleteRowWithName(
            final String name,
            final String table)
    throws CAMgmtException
    {
        final String sql = new StringBuilder("DELETE FROM ").append(table).append(" WHERE NAME=?").toString();
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean deleteRows(
            final String table)
    throws CAMgmtException
    {
        final String sql = "DELETE FROM " + table;
        Statement stmt = null;
        try
        {
            stmt = createStatement();
            stmt.executeQuery(sql);
            return true;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(stmt, null);
        }
    }

    void addCA(
            final CAEntry caEntry)
    throws CAMgmtException
    {
        if(caEntry instanceof X509CAEntry == false)
        {
            throw new CAMgmtException("unsupported CAEntry " + caEntry.getClass().getName());
        }

        X509CAEntry entry = (X509CAEntry) caEntry;
        String name = entry.getName();

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("INSERT INTO CA (");
        sqlBuilder.append("NAME, ART, SUBJECT, NEXT_SERIAL, NEXT_CRLNO, STATUS");
        sqlBuilder.append(", CRL_URIS, DELTACRL_URIS, OCSP_URIS, CACERT_URIS");
        sqlBuilder.append(", MAX_VALIDITY, CERT, SIGNER_TYPE, SIGNER_CONF");
        sqlBuilder.append(", CRLSIGNER_NAME, RESPONDER_NAME, CMPCONTROL_NAME");
        sqlBuilder.append(", DUPLICATE_KEY, DUPLICATE_SUBJECT, PERMISSIONS, NUM_CRLS, EXPIRATION_PERIOD");
        sqlBuilder.append(", VALIDITY_MODE, EXTRA_CONTROL");
        sqlBuilder.append(") VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        final String sql = sqlBuilder.toString();

        // insert to table ca
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            int idx = 1;
            ps.setString(idx++, name);
            ps.setInt(idx++, CertArt.X509PKC.getCode());
            ps.setString(idx++, entry.getSubject());

            long nextSerial = entry.getNextSerial();
            if(nextSerial < 0)
            {
                nextSerial = 0;
            }
            ps.setLong(idx++, nextSerial);

            ps.setInt(idx++, entry.getNextCRLNumber());
            ps.setString(idx++, entry.getStatus().getStatus());
            ps.setString(idx++, entry.getCrlUrisAsString());
            ps.setString(idx++, entry.getDeltaCrlUrisAsString());
            ps.setString(idx++, entry.getOcspUrisAsString());
            ps.setString(idx++, entry.getCacertUrisAsString());
            ps.setString(idx++, entry.getMaxValidity().toString());
            byte[] encodedCert = entry.getCertificate().getEncoded();
            ps.setString(idx++, Base64.toBase64String(encodedCert));
            ps.setString(idx++, entry.getSignerType());
            ps.setString(idx++, entry.getSignerConf());
            ps.setString(idx++, entry.getCrlSignerName());
            ps.setString(idx++, entry.getResponderName());
            ps.setString(idx++, entry.getCmpControlName());
            ps.setInt(idx++, entry.getDuplicateKeyMode().getMode());
            ps.setInt(idx++, entry.getDuplicateSubjectMode().getMode());
            ps.setString(idx++, Permission.toString(entry.getPermissions()));
            ps.setInt(idx++, entry.getNumCrls());
            ps.setInt(idx++, entry.getExpirationPeriod());
            ps.setString(idx++, entry.getValidityMode().name());
            ps.setString(idx++, entry.getExtraControl());

            ps.executeUpdate();

            // create serial sequence
            if(nextSerial > 0)
            {
                dataSource.createSequence(entry.getSerialSeqName(), nextSerial);
            }

            if(LOG.isInfoEnabled())
            {
                LOG.info("add CA '{}': {}", name, entry.toString(false, true));
            }
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        } catch (CertificateEncodingException | DataAccessException e)
        {
            throw new CAMgmtException(e.getMessage(), e);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void addCaAlias(
            final String aliasName,
            final String caName)
    throws CAMgmtException
    {
        final String sql = "INSERT INTO CAALIAS (NAME, CA_NAME) VALUES (?, ?)";

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, aliasName);
            ps.setString(2, caName);
            ps.executeUpdate();
            LOG.info("added CA alias '{}' for CA '{}'", aliasName, caName);
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void addCertprofile(
            final CertprofileEntry dbEntry)
    throws CAMgmtException
    {
        final String sql = "INSERT INTO PROFILE (NAME, ART, TYPE, CONF) VALUES (?, ?, ?, ?)";
        final String name = dbEntry.getName();

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, name);
            ps.setInt(2, CertArt.X509PKC.getCode());
            ps.setString(3, dbEntry.getType());
            String conf = dbEntry.getConf();
            ps.setString(4, conf);

            ps.executeUpdate();

            LOG.info("added profile '{}': {}", name, dbEntry);
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void addCertprofileToCA(
            final String profileName,
            final String profileLocalName,
            final String caName)
    throws CAMgmtException
    {
        final String sql = "INSERT INTO CA_HAS_PROFILE (CA_NAME, PROFILE_NAME, PROFILE_LOCALNAME) VALUES (?, ?, ?)";
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, caName);
            ps.setString(2, profileName);
            ps.setString(3, profileLocalName);
            ps.executeUpdate();
            LOG.info("added profile '{} (localname {})' to CA '{}'", profileName, profileLocalName, caName);
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void addCmpControl(
            final CmpControlEntry dbEntry)
    throws CAMgmtException
    {
        final String name = dbEntry.getName();

        final String sql = "INSERT INTO CMPCONTROL (NAME, CONF) VALUES (?, ?)";

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);

            int idx = 1;
            ps.setString(idx++, name);
            ps.setString(idx++, dbEntry.getConf());
            ps.executeUpdate();
            LOG.info("added CMP control: {}", dbEntry);
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void addCmpRequestor(
            final CmpRequestorEntry dbEntry)
    throws CAMgmtException
    {
        String name = dbEntry.getName();

        final String sql = "INSERT INTO REQUESTOR (NAME, CERT) VALUES (?, ?)";
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            int idx = 1;
            ps.setString(idx++, name);
            ps.setString(idx++, Base64.toBase64String(dbEntry.getCert().getEncoded()));
            ps.executeUpdate();
            if(LOG.isInfoEnabled())
            {
                LOG.info("added requestor '{}': {}", name, dbEntry.toString(false));
            }
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }catch(CertificateEncodingException e)
        {
            throw new CAMgmtException(e.getMessage(), e);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void addCmpRequestorToCA(
            final CAHasRequestorEntry requestor,
            final String caName)
    throws CAMgmtException
    {
        final String requestorName = requestor.getRequestorName();

        PreparedStatement ps = null;
        final String sql =
                "INSERT INTO CA_HAS_REQUESTOR (CA_NAME, REQUESTOR_NAME, RA, PERMISSIONS, PROFILES) VALUES (?, ?, ?, ?, ?)";
        try
        {
            boolean ra = requestor.isRa();
            String permissionText = Permission.toString(requestor.getPermissions());
            String profilesText = toString(requestor.getProfiles(), ",");

            ps = prepareStatement(sql);
            int idx = 1;
            ps.setString(idx++, caName);
            ps.setString(idx++, requestorName);

            setBoolean(ps, idx++, ra);
            ps.setString(idx++, permissionText);

            ps.setString(idx++, profilesText);

            ps.executeUpdate();
            LOG.info("added requestor '{}' to CA '{}': ra: {}; permission: {}; profile: {}",
                    requestorName, caName, ra, permissionText, profilesText);
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void addCrlSigner(
            final X509CrlSignerEntry dbEntry)
    throws CAMgmtException
    {
        String name = dbEntry.getName();
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("INSERT INTO CRLSIGNER (NAME, SIGNER_TYPE, SIGNER_CONF, SIGNER_CERT, CRL_CONTROL)");
        sqlBuilder.append(" VALUES (?, ?, ?, ?, ?)");
        final String sql = sqlBuilder.toString();

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            int idx = 1;
            ps.setString(idx++, name);
            ps.setString(idx++, dbEntry.getType());
            ps.setString(idx++, dbEntry.getConf());
            ps.setString(idx++, dbEntry.getCertificate() == null ? null :
                    Base64.toBase64String(dbEntry.getCertificate().getEncoded()));

            String crlControl = dbEntry.getCrlControl();
            ps.setString(idx++, crlControl);

            ps.executeUpdate();
            LOG.info("added CRL signer '{}': {}", name, dbEntry.toString(false, true));
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }catch(CertificateEncodingException e)
        {
            throw new CAMgmtException(e.getMessage(), e);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void addEnvParam(
            final String name,
            final String value)
    throws CAMgmtException
    {
        final String sql = "INSERT INTO ENVIRONMENT (NAME, VALUE2) VALUES (?, ?)";

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, name);
            ps.setString(2, value);
            ps.executeUpdate();
            LOG.info("added environment param '{}': {}", name, value);
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void addPublisher(
            final PublisherEntry dbEntry)
    throws CAMgmtException
    {
        String name = dbEntry.getName();
        final String sql = "INSERT INTO PUBLISHER (NAME, TYPE, CONF) VALUES (?, ?, ?)";

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, name);
            ps.setString(2, dbEntry.getType());
            String conf = dbEntry.getConf();
            ps.setString(3, conf);

            ps.executeUpdate();
            LOG.info("added publisher '{}': {}", name, dbEntry);
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void addPublisherToCA(
            final String publisherName,
            final String caName)
    throws CAMgmtException
    {
        final String sql = "INSERT INTO CA_HAS_PUBLISHER (CA_NAME, PUBLISHER_NAME) VALUES (?, ?)";
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, caName);
            ps.setString(2, publisherName);
            ps.executeUpdate();
            LOG.info("added publisher '{}' to CA '{}'", publisherName, caName);
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean changeCA(
            final ChangeCAEntry changeCAEntry,
            final SecurityFactory securityFactory)
    throws CAMgmtException
    {
        if(changeCAEntry instanceof X509ChangeCAEntry == false)
        {
            throw new CAMgmtException("unsupported ChangeCAEntry " + changeCAEntry.getClass().getName());
        }

        X509ChangeCAEntry entry = (X509ChangeCAEntry) changeCAEntry;
        String name = entry.getName();
        CAStatus status = entry.getStatus();
        X509Certificate cert = entry.getCert();
        List<String> crl_uris = entry.getCrlUris();
        List<String> delta_crl_uris = entry.getDeltaCrlUris();
        List<String> ocsp_uris = entry.getOcspUris();
        List<String> cacert_uris = entry.getCaCertUris();
        CertValidity max_validity = entry.getMaxValidity();
        String signer_type = entry.getSignerType();
        String signer_conf = entry.getSignerConf();
        String crlsigner_name = entry.getCrlSignerName();
        String responder_name = entry.getResponderName();
        String cmpcontrol_name = entry.getCmpControlName();
        DuplicationMode duplicate_key = entry.getDuplicateKeyMode();
        DuplicationMode duplicate_subject = entry.getDuplicateSubjectMode();
        Set<Permission> permissions = entry.getPermissions();
        Integer numCrls = entry.getNumCrls();
        Integer expirationPeriod = entry.getExpirationPeriod();
        ValidityMode validityMode = entry.getValidityMode();
        String extraControl = entry.getExtraControl();

        if(signer_type != null || signer_conf != null || cert != null)
        {
            final String sql = "SELECT SIGNER_TYPE, SIGNER_CONF, CERT FROM CA WHERE NAME=?";
            PreparedStatement stmt = null;
            ResultSet rs = null;

            try
            {
                stmt = prepareStatement(sql);
                stmt.setString(1, name);
                rs = stmt.executeQuery();
                if(rs.next() == false)
                {
                    throw new CAMgmtException("no CA '" + name + "' is defined");
                }

                String _signerType = rs.getString("SIGNER_TYPE");
                String _signerConf = rs.getString("SIGNER_CONF");
                String _b64Cert = rs.getString("CERT");
                if(signer_type != null)
                {
                    _signerType = signer_type;
                }

                if(signer_conf != null)
                {
                    _signerConf = getRealString(signer_conf);
                }

                X509Certificate _cert;
                if(cert != null)
                {
                    _cert = cert;
                }
                else
                {
                    try
                    {
                        _cert = X509Util.parseBase64EncodedCert(_b64Cert);
                    } catch (CertificateException | IOException e)
                    {
                        throw new CAMgmtException(
                                "could not parse the stored certificate for CA '" + name + "'" + e.getMessage(), e);
                    }
                }

                try
                {
                    List<String[]> signerConfs = CAManagerImpl.splitCASignerConfs(_signerConf);
                    for(String[] m : signerConfs)
                    {
                        String signerConf = m[1];
                        securityFactory.createSigner(_signerType, signerConf, _cert);
                    }
                } catch (SignerException e)
                {
                    throw new CAMgmtException(
                            "could not create signer for  CA '" + name + "'" + e.getMessage(), e);
                }
            }catch(SQLException e)
            {
                DataAccessException tEx = dataSource.translate(sql, e);
                throw new CAMgmtException(tEx.getMessage(), tEx);
            }finally
            {
                dataSource.releaseResources(stmt, rs);
            }
        }

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("UPDATE CA SET ");

        AtomicInteger index = new AtomicInteger(1);

        Integer iStatus = addToSqlIfNotNull(sqlBuilder, index, status, "STATUS");
        Integer iSubject = addToSqlIfNotNull(sqlBuilder, index, cert, "SUBJECT");
        Integer iCert = addToSqlIfNotNull(sqlBuilder, index, cert, "CERT");
        Integer iCrl_uris = addToSqlIfNotNull(sqlBuilder, index, crl_uris, "CRL_URIS");
        Integer iDelta_crl_uris = addToSqlIfNotNull(sqlBuilder, index, delta_crl_uris, "DELTACRL_URIS");
        Integer iOcsp_uris = addToSqlIfNotNull(sqlBuilder, index, ocsp_uris, "OCSP_URIS");
        Integer iCacert_uris = addToSqlIfNotNull(sqlBuilder, index, cacert_uris, "CACERT_URIS");
        Integer iMax_validity = addToSqlIfNotNull(sqlBuilder, index, max_validity, "MAX_VALIDITY");
        Integer iSigner_type = addToSqlIfNotNull(sqlBuilder, index, signer_type, "SIGNER_TYPE");
        Integer iSigner_conf = addToSqlIfNotNull(sqlBuilder, index, signer_conf, "SIGNER_CONF");
        Integer iCrlsigner_name = addToSqlIfNotNull(sqlBuilder, index, crlsigner_name, "CRLSIGNER_NAME");
        Integer iResponder_name = addToSqlIfNotNull(sqlBuilder, index, responder_name, "RESPONDER_NAME");
        Integer iCmpcontrol_name = addToSqlIfNotNull(sqlBuilder, index, cmpcontrol_name, "CMPCONTROL_NAME");
        Integer iDuplicate_key = addToSqlIfNotNull(sqlBuilder, index, duplicate_key, "DUPLICATE_KEY");
        Integer iDuplicate_subject = addToSqlIfNotNull(sqlBuilder, index, duplicate_subject, "DUPLICATE_SUBJECT");
        Integer iPermissions = addToSqlIfNotNull(sqlBuilder, index, permissions, "PERMISSIONS");
        Integer iNum_crls = addToSqlIfNotNull(sqlBuilder, index, numCrls, "NUM_CRLS");
        Integer iExpiration_period = addToSqlIfNotNull(sqlBuilder, index, expirationPeriod, "EXPIRATION_PERIOD");
        Integer iValidity_mode = addToSqlIfNotNull(sqlBuilder, index, validityMode, "VALIDITY_MODE");
        Integer iExtra_control = addToSqlIfNotNull(sqlBuilder, index, extraControl, "EXTRA_CONTROL");

        // delete the last ','
        sqlBuilder.deleteCharAt(sqlBuilder.length() - 1);
        sqlBuilder.append(" WHERE NAME=?");

        if(index.get() == 1)
        {
            return false;
        }
        int iName = index.get();

        final String sql = sqlBuilder.toString();
        StringBuilder m = new StringBuilder();
        PreparedStatement ps = null;

        try
        {
            ps = prepareStatement(sql);

            if(iStatus != null)
            {
                m.append("status: '").append(status.name()).append("'; ");
                ps.setString(iStatus, status.name());
            }

            if(iCert != null)
            {
                String subject = X509Util.getRFC4519Name(cert.getSubjectX500Principal());
                m.append("cert: '").append(subject).append("'; ");
                ps.setString(iSubject, subject);
                String base64Cert = Base64.toBase64String(cert.getEncoded());
                ps.setString(iCert, base64Cert);
            }

            if(iCrl_uris != null)
            {
                String txt = toString(crl_uris, ", ");
                m.append("crlUri: '").append(txt).append("'; ");
                ps.setString(iCrl_uris, txt);
            }

            if(iDelta_crl_uris != null)
            {
                String txt = toString(delta_crl_uris, ", ");
                m.append("deltaCrlUri: '").append(txt).append("'; ");
                ps.setString(iDelta_crl_uris, txt);
            }

            if(iOcsp_uris != null)
            {
                String txt = toString(ocsp_uris, ", ");
                m.append("ocspUri: '").append(txt).append("'; ");
                ps.setString(iOcsp_uris, txt);
            }

            if(iCacert_uris != null)
            {
                String txt = toString(cacert_uris, ", ");
                m.append("caCertUri: '").append(txt).append("'; ");
                ps.setString(iCacert_uris, txt);
            }

            if(iMax_validity != null)
            {
                String txt = max_validity.toString();
                m.append("maxValidity: '").append(txt).append("'; ");
                ps.setString(iMax_validity, txt);
            }

            if(iSigner_type != null)
            {
                m.append("signerType: '").append(signer_type).append("'; ");
                ps.setString(iSigner_type, signer_type);
            }

            if(iSigner_conf != null)
            {
                m.append("signerConf: '");
                m.append(SecurityUtil.signerConfToString(signer_conf, false, true));
                m.append("'; ");
                ps.setString(iSigner_conf, signer_conf);
            }

            if(iCrlsigner_name != null)
            {
                String txt = getRealString(crlsigner_name);
                m.append("crlSigner: '").append(txt).append("'; ");
                ps.setString(iCrlsigner_name, txt);
            }

            if(iResponder_name != null)
            {
                String txt = getRealString(responder_name);
                m.append("responder: '").append(txt).append("'; ");
                ps.setString(iResponder_name, txt);
            }

            if(iCmpcontrol_name != null)
            {
                String txt = getRealString(cmpcontrol_name);
                m.append("cmpControl: '").append(txt).append("'; ");
                ps.setString(iCmpcontrol_name, txt);
            }

            if(iDuplicate_key != null)
            {
                int mode = duplicate_key.getMode();
                m.append("duplicateKey: '").append(mode).append("'; ");
                ps.setInt(iDuplicate_key, mode);
            }

            if(iDuplicate_subject != null)
            {
                int mode = duplicate_subject.getMode();
                m.append("duplicateSubject: '").append(mode).append("'; ");
                ps.setInt(iDuplicate_subject, mode);
            }

            if(iPermissions != null)
            {
                String txt = Permission.toString(permissions);
                m.append("permission: '").append(txt).append("'; ");
                ps.setString(iPermissions, txt);
            }

            if(iNum_crls != null)
            {
                m.append("numCrls: '").append(numCrls).append("'; ");
                ps.setInt(iNum_crls, numCrls);
            }

            if(iExpiration_period != null)
            {
                m.append("expirationPeriod: '").append(numCrls).append("'; ");
                ps.setInt(iExpiration_period, expirationPeriod);
            }

            if(iValidity_mode != null)
            {
                String txt = validityMode.name();
                m.append("validityMode: '").append(txt).append("'; ");
                ps.setString(iValidity_mode, txt);
            }

            if(iExtra_control != null)
            {
                m.append("extraControl: '").append(extraControl).append("'; ");
                ps.setString(iExtra_control, extraControl);
            }

            ps.setString(iName, name);
            ps.executeUpdate();

            if(m.length() > 0)
            {
                m.deleteCharAt(m.length() - 1).deleteCharAt(m.length() - 1);
            }

            LOG.info("changed CA '{}': {}", name, m);
            return true;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }catch(CertificateEncodingException e)
        {
            throw new CAMgmtException(e.getMessage(), e);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    IdentifiedX509Certprofile changeCertprofile(
            final String name,
            String type,
            String conf,
            final CAManagerImpl caManager)
    throws CAMgmtException
    {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("UPDATE PROFILE SET ");

        AtomicInteger index = new AtomicInteger(1);

        StringBuilder m = new StringBuilder();

        if(type != null)
        {
            m.append("type: '").append(type).append("'; ");
        }
        if(conf != null)
        {
            m.append("conf: '").append(conf).append("'; ");
        }

        Integer iType = addToSqlIfNotNull(sqlBuilder, index, type, "TYPE");
        Integer iConf = addToSqlIfNotNull(sqlBuilder, index, conf, "CONF");
        sqlBuilder.deleteCharAt(sqlBuilder.length() - 1);
        sqlBuilder.append(" WHERE NAME=?");
        if(index.get() == 1)
        {
            return null;
        }

        CertprofileEntry currentDbEntry = createCertprofile(name);
        if(type == null)
        {
            type = currentDbEntry.getType();
        }
        if(conf == null)
        {
            conf = currentDbEntry.getConf();
        }

        type = getRealString(type);
        conf = getRealString(conf);

        CertprofileEntry newDbEntry = new CertprofileEntry(name, type, conf);
        IdentifiedX509Certprofile profile = caManager.createCertprofile(newDbEntry);
        if(profile == null)
        {
            return null;
        }

        final String sql = sqlBuilder.toString();

        boolean failed = true;
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            if(iType != null)
            {
                ps.setString(iType, type);
            }

            if(iConf != null)
            {
                ps.setString(iConf, getRealString(conf));
            }

            ps.setString(index.get(), name);
            ps.executeUpdate();

            if(m.length() > 0)
            {
                m.deleteCharAt(m.length() - 1).deleteCharAt(m.length() - 1);
            }

            LOG.info("changed profile '{}': {}", name, m);
            failed = false;
            return profile;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
            if(failed)
            {
                profile.shutdown();
            }
        }
    }

    CmpControl changeCmpControl(
            final String name,
            final String conf)
    throws CAMgmtException
    {
        if(conf == null)
        {
            return null;
        }

        CmpControlEntry newDbEntry = new CmpControlEntry(name, conf);
        CmpControl cmpControl;
        try
        {
            cmpControl = new CmpControl(newDbEntry);
        } catch (ConfigurationException e)
        {
            throw new CAMgmtException(e.getMessage(), e);
        }

        final String sql = "UPDATE CMPCONTROL SET CONF=? WHERE NAME=?";
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, conf);
            ps.setString(2, name);
            ps.executeUpdate();

            LOG.info("changed CMP control '{}': {}", name, conf);
            return cmpControl;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    CmpRequestorEntryWrapper changeCmpRequestor(
            final String name,
            final String base64Cert)
    throws CAMgmtException
    {
        CmpRequestorEntry newDbEntry = new CmpRequestorEntry(name, base64Cert);
        CmpRequestorEntryWrapper requestor = new CmpRequestorEntryWrapper();
        requestor.setDbEntry(newDbEntry);

        final String sql = "UPDATE REQUESTOR SET CERT=? WHERE NAME=?";
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            String b64Cert = getRealString(base64Cert);
            ps.setString(1, b64Cert);
            ps.setString(2, name);
            ps.executeUpdate();

            String subject = null;
            if(b64Cert != null)
            {
                try
                {
                    subject = X509Util.canonicalizName(
                            X509Util.parseBase64EncodedCert(b64Cert).getSubjectX500Principal());
                } catch (CertificateException | IOException e)
                {
                    subject = "ERROR";
                }
            }
            LOG.info("changed CMP requestor '{}': {}", name, subject);
            return requestor;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    CmpResponderEntryWrapper changeCmpResponder(
            final String name,
            String type,
            String conf,
            String base64Cert,
            final CAManagerImpl caManager)
    throws CAMgmtException
    {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("UPDATE RESPONDER SET ");

        AtomicInteger index = new AtomicInteger(1);
        Integer iType = addToSqlIfNotNull(sqlBuilder, index, type, "TYPE");
        Integer iConf = addToSqlIfNotNull(sqlBuilder, index, conf, "CONF");
        Integer iCert = addToSqlIfNotNull(sqlBuilder, index, base64Cert, "CERT");
        sqlBuilder.deleteCharAt(sqlBuilder.length() - 1);
        sqlBuilder.append(" WHERE NAME=?");

        if(index.get() == 1)
        {
            return null;
        }

        CmpResponderEntry dbEntry = createResponder(name);

        if(type == null)
        {
            type = dbEntry.getType();
        }

        if(conf == null)
        {
            conf = dbEntry.getConf();
        }

        if(base64Cert == null)
        {
            base64Cert = dbEntry.getBase64Cert();
        }

        CmpResponderEntry newDbEntry = new CmpResponderEntry(name, type, conf, base64Cert);
        CmpResponderEntryWrapper responder = caManager.createCmpResponder(newDbEntry);

        final String sql = sqlBuilder.toString();

        StringBuilder m = new StringBuilder();

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            if(iType != null)
            {
                String txt = type;
                ps.setString(iType, txt);
                m.append("type: '").append(txt).append("'; ");
            }

            if(iConf != null)
            {
                String txt = getRealString(conf);
                m.append("conf: '").append(SecurityUtil.signerConfToString(txt, false, true));
                ps.setString(iConf, txt);
            }

            if(iCert != null)
            {
                String txt = getRealString(base64Cert);
                m.append("cert: '");
                if(txt == null)
                {
                    m.append("null");
                }
                else
                {
                    try
                    {
                        String subject = X509Util.canonicalizName(
                                X509Util.parseBase64EncodedCert(txt).getSubjectX500Principal());
                        m.append(subject);
                    } catch (CertificateException | IOException e)
                    {
                        m.append("ERROR");
                    }
                }
                m.append("'; ");
                ps.setString(iCert, txt);
            }

            ps.setString(index.get(), name);

            ps.executeUpdate();

            if(m.length() > 0)
            {
                m.deleteCharAt(m.length() - 1).deleteCharAt(m.length() - 1);
            }
            LOG.info("changed CMP responder: {}", m);
            return responder;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    X509CrlSignerEntryWrapper changeCrlSigner(
            final String name,
            String signerType,
            String signerConf,
            String base64Cert,
            String crlControl,
            CAManagerImpl caManager)
    throws CAMgmtException
    {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("UPDATE CRLSIGNER SET ");

        AtomicInteger index = new AtomicInteger(1);

        Integer iSigner_type = addToSqlIfNotNull(sqlBuilder, index, signerType, "SIGNER_TYPE");
        Integer iSigner_conf = addToSqlIfNotNull(sqlBuilder, index, signerConf, "SIGNER_CONF");
        Integer iSigner_cert = addToSqlIfNotNull(sqlBuilder, index, base64Cert, "SIGNER_CERT");
        Integer iCrlControl = addToSqlIfNotNull(sqlBuilder, index, crlControl, "CRL_CONTROL");

        sqlBuilder.deleteCharAt(sqlBuilder.length() - 1);
        sqlBuilder.append(" WHERE NAME=?");

        if(index.get() == 1)
        {
            return null;
        }

        X509CrlSignerEntry dbEntry = createCrlSigner(name);
        if(signerType == null)
        {
            signerType = dbEntry.getType();
        }

        if("CA".equalsIgnoreCase(signerType))
        {
            signerConf = null;
            base64Cert = null;
        }
        else
        {
            if(signerConf == null)
            {
                signerConf = dbEntry.getConf();
            }

            if(base64Cert == null)
            {
                base64Cert = dbEntry.getBase64Cert();
            }
        }

        if(crlControl == null)
        {
            crlControl = dbEntry.getCrlControl();
        }

        try
        {
            dbEntry = new X509CrlSignerEntry(name, signerType, signerConf, base64Cert, crlControl);
        } catch (ConfigurationException e)
        {
            throw new CAMgmtException(e.getMessage(), e);
        }
        X509CrlSignerEntryWrapper crlSigner = caManager.createX509CrlSigner(dbEntry);

        final String sql = sqlBuilder.toString();

        PreparedStatement ps = null;
        try
        {
            StringBuilder m = new StringBuilder();

            ps = prepareStatement(sql);

            if(iSigner_type != null)
            {
                m.append("signerType: '").append(signerType).append("'; ");
                ps.setString(iSigner_type, signerType);
            }

            if(iSigner_conf != null)
            {
                String txt = getRealString(signerConf);
                m.append("signerConf: '").append(SecurityUtil.signerConfToString(txt, false, true)).append("'; ");
                ps.setString(iSigner_conf, txt);
            }

            if(iSigner_cert != null)
            {
                String txt = getRealString(base64Cert);
                String subject = null;
                if(txt != null)
                {
                    try
                    {
                        subject = X509Util.canonicalizName(
                                X509Util.parseBase64EncodedCert(txt).getSubjectX500Principal());
                    } catch (CertificateException | IOException e)
                    {
                        subject = "ERROR";
                    }
                }
                m.append("signerCert: '").append(subject).append("'; ");

                ps.setString(iSigner_cert, txt);
            }

            if(iCrlControl != null)
            {
                m.append("crlControl: '").append(crlControl).append("'; ");
                ps.setString(iCrlControl, crlControl);
            }

            ps.setString(index.get(), name);

            ps.executeUpdate();

            if(m.length() > 0)
            {
                m.deleteCharAt(m.length() - 1).deleteCharAt(m.length() - 1);
            }
            LOG.info("changed CRL signer '{}': {}", name, m);
            return crlSigner;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean changeEnvParam(
            final String name,
            final String value)
    throws CAMgmtException
    {
        if(value == null)
        {
            return false;
        }

        final String sql = "UPDATE ENVIRONMENT SET VALUE2=? WHERE NAME=?";

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, getRealString(value));
            ps.setString(2, name);
            ps.executeUpdate();
            LOG.info("changed environment param '{}': {}", name, value);
            return true;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    IdentifiedX509CertPublisher changePublisher(
            final String name,
            String type,
            String conf,
            final CAManagerImpl caManager)
    throws CAMgmtException
    {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("UPDATE PUBLISHER SET ");

        AtomicInteger index = new AtomicInteger(1);
        Integer iType = addToSqlIfNotNull(sqlBuilder, index, type, "TYPE");
        Integer iConf = addToSqlIfNotNull(sqlBuilder, index, conf, "CONF");
        sqlBuilder.deleteCharAt(sqlBuilder.length() - 1);
        sqlBuilder.append(" WHERE NAME=?");

        if(index.get() == 1)
        {
            return null;
        }

        PublisherEntry currentDbEntry = createPublisher(name);
        if(type == null)
        {
            type = currentDbEntry.getType();
        }

        if(conf == null)
        {
            conf = currentDbEntry.getConf();
        }

        PublisherEntry dbEntry = new PublisherEntry(name, type, conf);
        IdentifiedX509CertPublisher publisher = caManager.createPublisher(dbEntry);
        if(publisher == null)
        {
            return null;
        }

        final String sql = sqlBuilder.toString();

        PreparedStatement ps = null;
        try
        {
            StringBuilder m = new StringBuilder();
            ps = prepareStatement(sql);
            if(iType != null)
            {
                m.append("type: '").append(type).append("'; ");
                ps.setString(iType, type);
            }

            if(iConf != null)
            {
                String txt = getRealString(conf);
                m.append("conf: '").append(txt).append("'; ");
                ps.setString(iConf, getRealString(conf));
            }

            ps.setString(index.get(), name);
            ps.executeUpdate();

            if(m.length() > 0)
            {
                m.deleteCharAt(m.length() - 1).deleteCharAt(m.length() - 1);
            }
            LOG.info("changed publisher '{}': {}", name, m);
            return publisher;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean removeCA(
            final String caName)
    throws CAMgmtException
    {
        final String sql = "DELETE FROM CA WHERE NAME=?";

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, caName);
            return ps.executeUpdate() > 0;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean removeCaAlias(
            final String aliasName)
    throws CAMgmtException
    {
        final String sql = "DELETE FROM CAALIAS WHERE NAME=?";

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, aliasName);
            boolean b = ps.executeUpdate() > 0;
            if(b)
            {
                LOG.info("removed CA alias '{}'", aliasName);
            }
            return b;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean removeCertprofileFromCA(
            final String profileLocalName,
            final String caName)
    throws CAMgmtException
    {
        final String sql = "DELETE FROM CA_HAS_PROFILE WHERE CA_NAME=? AND PROFILE_LOCALNAME=?";
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, caName);
            ps.setString(2, profileLocalName);
            boolean b = ps.executeUpdate() > 0;
            if(b)
            {
                LOG.info("removed profile '{}' from CA '{}'", profileLocalName, caName);
            }
            return b;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean removeCmpRequestorFromCA(
            final String requestorName,
            final String caName)
    throws CAMgmtException
    {
        final String sql = "DELETE FROM CA_HAS_REQUESTOR WHERE CA_NAME=? AND REQUESTOR_NAME=?";
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, caName);
            ps.setString(2, requestorName);
            boolean b = ps.executeUpdate() > 0;
            if(b)
            {
                LOG.info("removed requestor '{}' from CA '{}'", requestorName, caName);
            }
            return b;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean removePublisherFromCA(
            final String publisherName,
            final String caName)
    throws CAMgmtException
    {
        final String sql = "DELETE FROM CA_HAS_PUBLISHER WHERE CA_NAME=? AND PUBLISHER_NAME=?";
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, caName);
            ps.setString(2, publisherName);
            boolean b = ps.executeUpdate() > 0;
            if(b)
            {
                LOG.info("removed publisher '{}' from CA '{}'", publisherName, caName);
            }
            return b;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean revokeCa(
            final String caName,
            final CertRevocationInfo revocationInfo)
    throws CAMgmtException
    {
        String sql = "UPDATE CA SET REVOKED=?, REV_REASON=?, REV_TIME=?, REV_INV_TIME=? WHERE NAME=?";
        PreparedStatement ps = null;
        try
        {
            if(revocationInfo.getInvalidityTime() == null)
            {
                revocationInfo.setInvalidityTime(revocationInfo.getRevocationTime());
            }

            ps = prepareStatement(sql);
            int i = 1;
            setBoolean(ps, i++, true);
            ps.setInt(i++, revocationInfo.getReason().getCode());
            ps.setLong(i++, revocationInfo.getRevocationTime().getTime() / 1000);
            ps.setLong(i++, revocationInfo.getInvalidityTime().getTime() / 1000);
            ps.setString(i++, caName);
            boolean b = ps.executeUpdate() > 0;
            if(b)
            {
                LOG.info("revoked CA '{}'", caName);
            }
            return b;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void addCmpResponder(
            final CmpResponderEntry dbEntry)
    throws CAMgmtException
    {
        final String sql = "INSERT INTO RESPONDER (NAME, TYPE, CONF, CERT) VALUES (?, ?, ?, ?)";

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            int idx = 1;
            ps.setString(idx++, dbEntry.getName());
            ps.setString(idx++, dbEntry.getType());
            ps.setString(idx++, dbEntry.getConf());

            String b64Cert = null;
            X509Certificate cert = dbEntry.getCertificate();
            if(cert != null)
            {
                b64Cert = Base64.toBase64String(dbEntry.getCertificate().getEncoded());
            }
            ps.setString(idx++, b64Cert);

            ps.executeUpdate();

            LOG.info("changed responder: {}", dbEntry.toString(false, true));
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }catch(CertificateEncodingException e)
        {
            throw new CAMgmtException(e.getMessage(), e);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean unlockCA()
    throws DataAccessException, CAMgmtException
    {
        final String sql = "DELETE FROM SYSTEM_EVENT WHERE NAME='LOCK'";
        Statement stmt = null;
        try
        {
            stmt = createStatement();
            stmt.execute(sql);
            return stmt.getUpdateCount() > 0;
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        } finally
        {
            dataSource.releaseResources(stmt, null);
        }
    }

    boolean unrevokeCa(
            final String caName)
    throws CAMgmtException
    {
        LOG.info("Unrevoking of CA '{}'", caName);

        final String sql = "UPDATE CA SET REVOKED=?, REV_REASON=?, REV_TIME=?, REV_INV_TIME=? WHERE NAME=?";
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            int i = 1;
            setBoolean(ps, i++, false);
            ps.setNull(i++, Types.INTEGER);
            ps.setNull(i++, Types.INTEGER);
            ps.setNull(i++, Types.INTEGER);
            ps.setString(i++, caName);
            return ps.executeUpdate() > 0;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean addUser(
            final AddUserEntry userEntry)
    throws CAMgmtException
    {
        final String name = userEntry.getName();
        Integer existingId = executeGetUserIdSql(name);
        if(existingId != null)
        {
            throw new CAMgmtException("user named '" + name + " ' already exists");
        }

        String hashedPassword;
        try
        {
            hashedPassword = PasswordHash.createHash(userEntry.getPassword().toCharArray());
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e)
        {
            throw new CAMgmtException(e);
        }
        UserEntry _userEntry = new UserEntry(name, hashedPassword, userEntry.getCnRegex());
        final int tries = 3;

        for(int i = 0; i < tries; i++)
        {
            int tmpId = (i == 0) ? name.hashCode() : random.nextInt();
            try
            {
                executeAddUserSql(tmpId, _userEntry);
                break;
            }catch(DataAccessException e)
            {
                if(e instanceof DuplicateKeyException && i < tries - 1)
                {
                    continue;
                }
                else
                {
                    throw new CAMgmtException(e);
                }
            }
        }

        LOG.info("added user '{}'", name);

        return true;
    }

    private Integer executeGetUserIdSql(
            final String user)
    throws CAMgmtException
    {
        final String sql = dataSource.createFetchFirstSelectSQL("ID FROM USERNAME WHERE NAME=?", 1);
        ResultSet rs = null;
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);

            int idx = 1;
            ps.setString(idx++, user);
            rs = ps.executeQuery();
            if(rs.next())
            {
                return rs.getInt("ID");
            } else
            {
                return null;
            }
        }catch(SQLException e)
        {
            throw new CAMgmtException(dataSource.translate(sql, e));
        } finally
        {
            dataSource.releaseResources(ps, rs);
        }
    }

    private void executeAddUserSql(
            final int id,
            final UserEntry userEntry)
    throws DataAccessException, CAMgmtException
    {

        final String sql = "INSERT INTO USERNAME (ID, NAME, PASSWORD, CN_REGEX) VALUES (?, ?, ?, ?)";

        PreparedStatement ps = null;

        try
        {
            ps = prepareStatement(sql);
            int idx = 1;
            ps.setInt(idx++, id);
            ps.setString(idx++, userEntry.getName());
            ps.setString(idx++, userEntry.getHashedPassword());
            ps.setString(idx++, userEntry.getCnRegex());
            ps.executeUpdate();
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        } finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean changeUser(
            final String username,
            final String password,
            final String cnRegex)
    throws CAMgmtException
    {
        Integer existingId = executeGetUserIdSql(username);
        if(existingId == null)
        {
            throw new CAMgmtException("user named '" + username + " ' does not exist");
        }

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("UPDATE USERNAME SET ");

        AtomicInteger index = new AtomicInteger(1);
        Integer iPassword = addToSqlIfNotNull(sqlBuilder, index, password, "PASSWORD");
        Integer iCnRegex = addToSqlIfNotNull(sqlBuilder, index, cnRegex, "CN_REGEX");
        sqlBuilder.deleteCharAt(sqlBuilder.length() - 1);
        sqlBuilder.append(" WHERE NAME=?");

        if(index.get() == 1)
        {
            return false;
        }

        final String sql = sqlBuilder.toString();

        StringBuilder m = new StringBuilder();

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            if(iPassword != null)
            {
                String txt = getRealString(password);
                ps.setString(iPassword, txt);
                m.append("password: ****; ");
            }

            if(iCnRegex != null)
            {
                m.append("CnRegex: '").append(cnRegex);
                ps.setString(iCnRegex, cnRegex);
            }

            ps.setString(index.get(), username);

            ps.executeUpdate();

            if(m.length() > 0)
            {
                m.deleteCharAt(m.length() - 1).deleteCharAt(m.length() - 1);
            }
            LOG.info("changed user: {}", m);
            return true;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    UserEntry getUser(
            final String username)
    throws CAMgmtException
    {
        final String sql = dataSource.createFetchFirstSelectSQL("PASSWORD, CN_REGEX FROM USERNAME WHERE NAME=?", 1);
        ResultSet rs = null;
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);

            int idx = 1;
            ps.setString(idx++, username);
            rs = ps.executeQuery();
            if(rs.next())
            {
                String hashedPassword = rs.getString("PASSWORD");
                String cnRegex = rs.getString("CN_REGEX");
                return new UserEntry(username, hashedPassword, cnRegex);
            } else
            {
                return null;
            }
        }catch(SQLException e)
        {
            throw new CAMgmtException(dataSource.translate(sql, e));
        } finally
        {
            dataSource.releaseResources(ps, rs);
        }
    }

    boolean addScep(
            final ScepEntry scepEntry)
    throws CAMgmtException
    {
        String name = scepEntry.getName();
        final String sql = "INSERT INTO SCEP (NAME, CA_NAME, PROFILE_NAME) VALUES (?, ?, ?)";

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, name);
            ps.setString(2, scepEntry.getCaName());
            ps.setString(3, scepEntry.getProfileName());

            ps.executeUpdate();
            LOG.info("added SCEP '{}': {}", name, scepEntry);
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }

        return true;
    }

    boolean removeScep(
            final String name)
    throws CAMgmtException
    {
        final String sql = "DELETE FROM SCEP WHERE NAME=?";

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    boolean changeScep(
            final String name,
            final String caName,
            final String profileName)
    throws CAMgmtException
    {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("UPDATE SCEP SET ");

        AtomicInteger index = new AtomicInteger(1);
        Integer iCaName = addToSqlIfNotNull(sqlBuilder, index, caName, "CA_NAME");
        Integer iProfileName = addToSqlIfNotNull(sqlBuilder, index, profileName, "Profile_Name");
        sqlBuilder.deleteCharAt(sqlBuilder.length() - 1);
        sqlBuilder.append(" WHERE NAME=?");

        if(index.get() == 1)
        {
            return false;
        }

        final String sql = sqlBuilder.toString();

        StringBuilder m = new StringBuilder();

        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);
            if(iCaName != null)
            {
                ps.setString(iCaName, caName);
                m.append("CA-Name: ").append("'").append(caName).append("'");
            }

            if(iProfileName != null)
            {
                ps.setString(iProfileName, profileName);
                m.append("Profile-Name: ").append("'").append(profileName).append("'");
            }

            ps.setString(index.get(), name);

            ps.executeUpdate();

            if(m.length() > 0)
            {
                m.deleteCharAt(m.length() - 1).deleteCharAt(m.length() - 1);
            }
            LOG.info("changed SCEP: {}", m);
            return true;
        }catch(SQLException e)
        {
            DataAccessException tEx = dataSource.translate(sql, e);
            throw new CAMgmtException(tEx.getMessage(), tEx);
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    ScepEntry getScep(
            final String name)
    throws CAMgmtException
    {
        final String sql = dataSource.createFetchFirstSelectSQL("CA_NAME, PROFILE_NAME FROM SCEP WHERE NAME=?", 1);
        ResultSet rs = null;
        PreparedStatement ps = null;
        try
        {
            ps = prepareStatement(sql);

            int idx = 1;
            ps.setString(idx++, name);
            rs = ps.executeQuery();
            if(rs.next())
            {
                String caName = rs.getString("CA_NAME");
                String profileNmae = rs.getString("PROFILE_NAME");
                return new ScepEntry(name, caName, profileNmae);
            } else
            {
                return null;
            }
        }catch(SQLException e)
        {
            throw new CAMgmtException(dataSource.translate(sql, e));
        } finally
        {
            dataSource.releaseResources(ps, rs);
        }
    }

    private static void setBoolean(
            final PreparedStatement ps,
            final int index,
            final boolean b)
    throws SQLException
    {
        ps.setInt(index, b ? 1 : 0);
    }

    private static Integer addToSqlIfNotNull(
            final StringBuilder sqlBuilder,
            final AtomicInteger index,
            final Object columnObj,
            final String columnName)
    {
        if(columnObj == null)
        {
            return null;
        }

        sqlBuilder.append(columnName).append("=?,");
        return index.getAndIncrement();
    }

    private static Set<Permission> getPermissions(
            final String permissionsText)
    throws CAMgmtException
    {
        ParamChecker.assertNotBlank("permissionsText", permissionsText);

        List<String> l = StringUtil.split(permissionsText, ", ");
        Set<Permission> permissions = new HashSet<>();
        for(String permissionText : l)
        {
            Permission p = Permission.getPermission(permissionText);
            if(p == null)
            {
                throw new CAMgmtException("unknown permission " + permissionText);
            }
            if(p == Permission.ALL)
            {
                permissions.clear();
                permissions.add(p);
                break;
            }
            else
            {
                permissions.add(p);
            }
        }

        return permissions;
    }

    private static String toString(
            final Collection<String> tokens,
            final String seperator)
    {
        return StringUtil.collectionAsString(tokens, seperator);
    }

    private static String getRealString(
            final String s)
    {
        return CAManager.NULL.equalsIgnoreCase(s) ? null : s;
    }

}

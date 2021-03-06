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

package org.xipki.ca.server.impl.store;

import java.io.IOException;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CRLException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERPrintableString;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.util.encoders.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.ca.api.OperationException;
import org.xipki.ca.api.OperationException.ErrorCode;
import org.xipki.ca.api.RequestorInfo;
import org.xipki.ca.api.X509CertWithDBCertId;
import org.xipki.ca.api.publisher.X509CertificateInfo;
import org.xipki.ca.server.impl.CertRevInfoWithSerial;
import org.xipki.ca.server.impl.CertStatus;
import org.xipki.ca.server.impl.SubjectKeyProfileBundle;
import org.xipki.ca.server.mgmt.api.CertArt;
import org.xipki.common.CRLReason;
import org.xipki.common.CertRevocationInfo;
import org.xipki.common.LruCache;
import org.xipki.common.ObjectIdentifiers;
import org.xipki.common.ParamChecker;
import org.xipki.common.util.SecurityUtil;
import org.xipki.common.util.StringUtil;
import org.xipki.common.util.X509Util;
import org.xipki.datasource.api.DataSourceWrapper;
import org.xipki.datasource.api.exception.DataAccessException;
import org.xipki.datasource.api.exception.DataIntegrityViolationException;
import org.xipki.datasource.api.exception.DuplicateKeyException;

/**
 * @author Lijun Liao
 */

class CertStoreQueryExecutor
{
    private static final Logger LOG = LoggerFactory.getLogger(CertStoreQueryExecutor.class);

    private final DataSourceWrapper dataSource;

    private final CertBasedIdentityStore caInfoStore;
    private final NameIdStore requestorInfoStore;
    private final NameIdStore certprofileStore;
    private final NameIdStore publisherStore;

    private final SecureRandom random = new SecureRandom();
    private final LruCache<String, Integer> usernameIdCache = new LruCache<>(500);

    CertStoreQueryExecutor(
            final DataSourceWrapper dataSource)
    throws DataAccessException
    {
        this.dataSource = dataSource;

        this.caInfoStore = initCertBasedIdentyStore("CS_CA");
        this.requestorInfoStore = initNameIdStore("CS_REQUESTOR");
        this.certprofileStore = initNameIdStore("CS_PROFILE");
        this.publisherStore = initNameIdStore("CS_PUBLISHER");
    }

    private CertBasedIdentityStore initCertBasedIdentyStore(
            final String table)
    throws DataAccessException
    {
        final String sql =
                new StringBuilder("SELECT ID, SUBJECT, FP_CERT, CERT FROM ").append(table).toString();
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);
        try
        {
            rs = ps.executeQuery();
            List<CertBasedIdentityEntry> caInfos = new LinkedList<>();
            while(rs.next())
            {
                int id = rs.getInt("ID");
                String subject = rs.getString("SUBJECT");
                String hexSha1Fp = rs.getString("FP_CERT");
                String b64Cert = rs.getString("CERT");

                CertBasedIdentityEntry caInfoEntry = new CertBasedIdentityEntry(id, subject, hexSha1Fp, b64Cert);
                caInfos.add(caInfoEntry);
            }

            return new CertBasedIdentityStore(table, caInfos);
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    private NameIdStore initNameIdStore(
            final String tableName)
    throws DataAccessException
    {
        final String sql = new StringBuilder("SELECT ID, NAME FROM ").append(tableName).toString();
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            rs = ps.executeQuery();
            Map<String, Integer> entries = new HashMap<>();

            while(rs.next())
            {
                int id = rs.getInt("ID");
                String name = rs.getString("NAME");
                entries.put(name, id);
            }

            return new NameIdStore(tableName, entries);
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    /**
     * @throws SQLException if there is problem while accessing database.
     * @throws NoSuchAlgorithmException
     * @throws CertificateEncodingException
     */
    void addCert(
            final X509CertWithDBCertId issuer,
            final X509CertWithDBCertId certificate,
            final byte[] encodedSubjectPublicKey,
            final String certprofileName,
            final RequestorInfo requestor,
            final String user)
    throws DataAccessException, OperationException
    {
        final String SQL_ADD_CERT =
                "INSERT INTO CERT" +
                " (ID, ART, LAST_UPDATE, SERIAL, SUBJECT, NOTBEFORE, NOTAFTER, REVOKED, PROFILE_ID," +
                " CA_ID, REQUESTOR_ID, USER_ID, FP_PK, FP_SUBJECT, EE)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        final String SQL_ADD_RAWCERT = "INSERT INTO RAWCERT (CERT_ID, FP, CERT) VALUES (?, ?, ?)";

        Integer userId = (user == null) ? null : getUserId(user);
        int certId = nextCertId();
        int caId = getCaId(issuer);
        X509Certificate cert = certificate.getCert();
        // the profile name of self signed CA certificate may not be contained in the
        // table CS_PROFILE
        if(cert.getIssuerDN().equals(cert.getSubjectDN()))
        {
            addCertprofileName(certprofileName);
        }
        int certprofileId = getCertprofileId(certprofileName);
        Integer requestorId = (requestor == null) ? null : getRequestorId(requestor.getName());

        String fpPK = fp(encodedSubjectPublicKey);
        String fpSubject = X509Util.sha1sum_canonicalized_name(cert.getSubjectX500Principal());
        String fpCert = fp(certificate.getEncodedCert());
        String b64Cert = Base64.toBase64String(certificate.getEncodedCert());

        Connection conn = null;
        PreparedStatement[] pss = borrowPreparedStatements(SQL_ADD_CERT, SQL_ADD_RAWCERT);

        try
        {
            PreparedStatement ps_addcert = pss[0];
            PreparedStatement ps_addRawcert = pss[1];
            // all statements have the same connection
            conn = ps_addcert.getConnection();

            // cert
            int idx = 2;
            ps_addcert.setInt(idx++, CertArt.X509PKC.getCode());
            ps_addcert.setLong(idx++, System.currentTimeMillis()/1000);
            ps_addcert.setLong(idx++, cert.getSerialNumber().longValue());
            ps_addcert.setString(idx++, certificate.getSubject());
            ps_addcert.setLong(idx++, cert.getNotBefore().getTime()/1000);
            ps_addcert.setLong(idx++, cert.getNotAfter().getTime()/1000);
            setBoolean(ps_addcert, idx++, false);
            ps_addcert.setInt(idx++, certprofileId);
            ps_addcert.setInt(idx++, caId);

            if(requestorId != null)
            {
                ps_addcert.setInt(idx++, requestorId.intValue());
            }
            else
            {
                ps_addcert.setNull(idx++, Types.INTEGER);
            }

            if(userId != null)
            {
                ps_addcert.setInt(idx++, userId.intValue());
            }
            else
            {
                ps_addcert.setNull(idx++, Types.INTEGER);
            }

            ps_addcert.setString(idx++, fpPK);
            ps_addcert.setString(idx++, fpSubject);

            boolean isEECert = cert.getBasicConstraints() == -1;
            ps_addcert.setInt(idx++, isEECert ? 1 : 0);

            // rawcert
            idx = 2;
            ps_addRawcert.setString(idx++, fpCert);
            ps_addRawcert.setString(idx++, b64Cert);

            final int tries = 3;
            for(int i = 0; i < tries; i++)
            {
                if(i > 0)
                {
                    certId = nextCertId();
                }
                certificate.setCertId(certId);

                ps_addcert.setInt(1, certId);
                ps_addRawcert.setInt(1, certId);

                final boolean origAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);

                String sql = null;
                try
                {
                    sql = SQL_ADD_CERT;
                    ps_addcert.executeUpdate();

                    sql = SQL_ADD_RAWCERT;
                    ps_addRawcert.executeUpdate();

                    sql = "(commit add cert to CA certstore)";
                    conn.commit();
                }catch(SQLException e)
                {
                    conn.rollback();
                    DataAccessException tEx = dataSource.translate(sql, e);
                    if(tEx instanceof DuplicateKeyException && i < tries - 1)
                    {
                        continue;
                    }

                    LOG.error("datasource {} SQLException while adding certificate with id {}: {}",
                            dataSource.getDatasourceName(), certId, e.getMessage());
                    throw e;
                }
                finally
                {
                    conn.setAutoCommit(origAutoCommit);
                }

                break;
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(null, e);
        }
        finally
        {
            try
            {
                for(PreparedStatement ps : pss)
                {
                    try
                    {
                        ps.close();
                    }catch(Throwable t)
                    {
                        LOG.warn("could not close PreparedStatement", t);
                    }
                }
            }finally
            {
                dataSource.returnConnection(conn);
            }
        }
    }

    void addToPublishQueue(
            final String publisherName,
            final int certId,
            final X509CertWithDBCertId caCert)
    throws DataAccessException, OperationException
    {
        final String sql = "INSERT INTO PUBLISHQUEUE (PUBLISHER_ID, CA_ID, CERT_ID) VALUES (?, ?, ?)";
        PreparedStatement ps = borrowPreparedStatement(sql);
        int caId = getCaId(caCert);
        try
        {
            int publisherId = getPublisherId(publisherName);
            int idx = 1;
            ps.setInt(idx++, publisherId);
            ps.setInt(idx++, caId);
            ps.setInt(idx++, certId);
            ps.executeUpdate();
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, null);
        }
    }

    void removeFromPublishQueue(
            final String publisherName,
            final int certId)
    throws DataAccessException
    {
        final String sql = "DELETE FROM PUBLISHQUEUE WHERE PUBLISHER_ID=? AND CERT_ID=?";
        PreparedStatement ps = borrowPreparedStatement(sql);
        try
        {
            int publisherId = getPublisherId(publisherName);
            int idx = 1;
            ps.setInt(idx++, publisherId);
            ps.setInt(idx++, certId);
            ps.executeUpdate();
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, null);
        }
    }

    long getMaxIdOfDeltaCRLCache(
            final X509CertWithDBCertId caCert)
    throws OperationException, DataAccessException
    {
        String sql = "SELECT MAX(ID) FROM DELTACRL_CACHE WHERE CA_ID=?";
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int caId = getCaId(caCert);
            ps.setInt(1, caId);

            ResultSet rs = ps.executeQuery();
            if(rs.next())
            {
                return rs.getLong(1);
            }
            else
            {
                return 0;
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, null);
        }
    }

    public void clearDeltaCRLCache(
            final X509CertWithDBCertId caCert,
            final long maxId)
    throws OperationException, DataAccessException
    {
        final String sql = "DELETE FROM DELTACRL_CACHE WHERE ID<? AND CA_ID=?";

        PreparedStatement ps = borrowPreparedStatement(sql);
        try
        {
            ps.setLong(1, maxId + 1);
            int caId = getCaId(caCert);
            ps.setInt(2, caId);
            ps.executeUpdate();
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, null);
        }
    }

    void clearPublishQueue(
            final X509CertWithDBCertId caCert,
            final String publisherName)
    throws OperationException, DataAccessException
    {
        StringBuilder sqlBuilder = new StringBuilder("DELETE FROM PUBLISHQUEUE");

        if(caCert != null || publisherName != null)
        {
            sqlBuilder.append(" WHERE");
            if(caCert != null)
            {
                sqlBuilder.append(" CA_ID=?");
                if(publisherName != null)
                {
                    sqlBuilder.append(" AND");
                }
            }

            if(publisherName != null)
            {
                sqlBuilder.append(" PUBLISHER_ID=?");
            }
        }

        String sql = sqlBuilder.toString();
        PreparedStatement ps = borrowPreparedStatement(sql);
        try
        {
            int idx = 1;
            if(caCert != null)
            {
                int caId = getCaId(caCert);
                ps.setInt(idx++, caId);
            }

            if(publisherName != null)
            {
                int publisherId = getPublisherId(publisherName);
                ps.setInt(idx++, publisherId);
            }
            ps.executeUpdate();
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, null);
        }
    }

    int getMaxCrlNumber(
            final X509CertWithDBCertId caCert)
    throws DataAccessException, OperationException
    {
        final String sql = "SELECT MAX(CRL_NO) FROM CRL WHERE CA_ID=?";
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int caId = getCaId(caCert);
            ps.setInt(1, caId);

            rs = ps.executeQuery();
            int maxCrlNumber = 0;
            if(rs.next())
            {
                maxCrlNumber = rs.getInt(1);
                if (maxCrlNumber < 0)
                {
                    maxCrlNumber = 0;
                }
            }

            return maxCrlNumber;
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    Long getThisUpdateOfCurrentCRL(
            final X509CertWithDBCertId caCert)
    throws DataAccessException, OperationException
    {
        final String sql = "SELECT MAX(THISUPDATE) FROM CRL WHERE CA_ID=?";
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int caId = getCaId(caCert);
            ps.setInt(1, caId);

            rs = ps.executeQuery();
            long thisUpdateOfCurrentCRL = 0;
            if(rs.next())
            {
                thisUpdateOfCurrentCRL = rs.getLong(1);
            }

            return thisUpdateOfCurrentCRL;
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    boolean hasCRL(
            final X509CertWithDBCertId caCert)
    throws DataAccessException
    {
        Integer caId =  caInfoStore.getCaIdForCert(caCert.getEncodedCert());
        if(caId == null)
        {
            return false;
        }

        final String sql = "SELECT COUNT(*) FROM CRL WHERE CA_ID = ?";
        PreparedStatement ps = null;
        ResultSet rs = null;
        try
        {
            ps = borrowPreparedStatement(sql);
            ps.setInt(1, caId);
            rs = ps.executeQuery();
            if(rs.next())
            {
                return rs.getInt(1) > 0;
            } else
            {
                return false;
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    void addCRL(
            final X509CertWithDBCertId caCert,
            final X509CRL crl)
    throws DataAccessException, CRLException, OperationException
    {
        byte[] encodedExtnValue = crl.getExtensionValue(Extension.cRLNumber.getId());
        Long crlNumber = null;
        if(encodedExtnValue != null)
        {
            byte[] extnValue = DEROctetString.getInstance(encodedExtnValue).getOctets();
            crlNumber = ASN1Integer.getInstance(extnValue).getPositiveValue().longValue();
        }

        encodedExtnValue = crl.getExtensionValue(Extension.deltaCRLIndicator.getId());
        Long baseCrlNumber = null;
        if(encodedExtnValue != null)
        {
            byte[] extnValue = DEROctetString.getInstance(encodedExtnValue).getOctets();
            baseCrlNumber = ASN1Integer.getInstance(extnValue).getPositiveValue().longValue();
        }

        final String sql =
                "INSERT INTO CRL (ID, CA_ID, CRL_NO, THISUPDATE, NEXTUPDATE, DELTACRL, BASECRL_NO, CRL)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        int currentMaxCrlId = (int) dataSource.getMax(null, "CRL", "ID");
        int crlId = currentMaxCrlId + 1;

        PreparedStatement ps = null;

        try
        {
            int caId = getCaId(caCert);
            ps = borrowPreparedStatement(sql);

            int idx = 1;
            ps.setInt(idx++, crlId);
            ps.setInt(idx++, caId);
            if(crlNumber != null)
            {
                ps.setInt(idx++, crlNumber.intValue());
            }
            else
            {
                ps.setNull(idx++, Types.INTEGER);
            }
            Date d = crl.getThisUpdate();
            ps.setLong(idx++, d.getTime()/1000);
            d = crl.getNextUpdate();
            if(d != null)
            {
                ps.setLong(idx++, d.getTime()/1000);
            }
            else
            {
                ps.setNull(idx++, Types.BIGINT);
            }

            ps.setInt(idx++, baseCrlNumber != null ? 1 : 0);

            if(baseCrlNumber != null)
            {
                ps.setLong(idx++, baseCrlNumber);
            }
            else
            {
                ps.setNull(idx++, Types.BIGINT);
            }

            byte[] encodedCrl = crl.getEncoded();
            String b64Crl = Base64.toBase64String(encodedCrl);
            ps.setString(idx++, b64Crl);

            ps.executeUpdate();
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, null);
        }
    }

    X509CertWithRevocationInfo revokeCert(
            final X509CertWithDBCertId caCert,
            final BigInteger serialNumber,
            final CertRevocationInfo revInfo,
            final boolean force,
            final boolean publishToDeltaCRLCache)
    throws OperationException, DataAccessException
    {
        X509CertWithRevocationInfo certWithRevInfo = getCertWithRevocationInfo(caCert, serialNumber);
        if(certWithRevInfo == null)
        {
            LOG.warn("certificate with issuer='{}' and serialNumber={} does not exist",
                    caCert.getSubject(), serialNumber);
            return null;
        }

        CertRevocationInfo currentRevInfo = certWithRevInfo.getRevInfo();
        if(currentRevInfo != null)
        {
            CRLReason currentReason = currentRevInfo.getReason();
            if(currentReason == CRLReason.CERTIFICATE_HOLD)
            {
                if(revInfo.getReason() == CRLReason.CERTIFICATE_HOLD)
                {
                    throw new OperationException(ErrorCode.CERT_REVOKED,
                            "certificate already issued with the requested reason " + currentReason.getDescription());
                }
                else
                {
                    revInfo.setRevocationTime(currentRevInfo.getRevocationTime());
                    revInfo.setInvalidityTime(currentRevInfo.getInvalidityTime());
                }
            }
            else if(force == false)
            {
                throw new OperationException(ErrorCode.CERT_REVOKED,
                        "certificate already issued with reason " + currentReason.getDescription());
            }
        }

        final String SQL_REVOKE_CERT = "UPDATE CERT" +
                " SET LAST_UPDATE=?, REVOKED=?, REV_TIME=?, REV_INV_TIME=?, REV_REASON=?" +
                " WHERE ID=?";
        PreparedStatement ps = borrowPreparedStatement(SQL_REVOKE_CERT);

        int certId = certWithRevInfo.getCert().getCertId().intValue();
        try
        {
            int idx = 1;
            ps.setLong(idx++, new Date().getTime()/1000);
            setBoolean(ps, idx++, true);
            ps.setLong(idx++, revInfo.getRevocationTime().getTime()/1000);
            if(revInfo.getInvalidityTime() != null)
            {
                ps.setLong(idx++, revInfo.getInvalidityTime().getTime()/1000);
            }else
            {
                ps.setNull(idx++, Types.BIGINT);
            }

            ps.setInt(idx++, revInfo.getReason().getCode());
            ps.setLong(idx++, certId);

            int count = ps.executeUpdate();
            if(count != 1)
            {
                String message;
                if(count > 1)
                {
                    message = count + " rows modified, but exactly one is expected";
                }
                else
                {
                    message = "no row is modified, but exactly one is expected";
                }
                throw new OperationException(ErrorCode.SYSTEM_FAILURE, message);
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(SQL_REVOKE_CERT, e);
        }finally
        {
            releaseDbResources(ps, null);
        }

        if(publishToDeltaCRLCache)
        {
            Integer caId = getCaId(caCert); // must not be null
            publishToDeltaCRLCache(caId, certWithRevInfo.getCert().getCert().getSerialNumber());
        }

        certWithRevInfo.setRevInfo(revInfo);
        return certWithRevInfo;
    }

    X509CertWithDBCertId unrevokeCert(
            final X509CertWithDBCertId caCert,
            final BigInteger serialNumber,
            final boolean force,
            final boolean publishToDeltaCRLCache)
    throws OperationException, DataAccessException
    {
        X509CertWithRevocationInfo certWithRevInfo = getCertWithRevocationInfo(caCert, serialNumber);
        if(certWithRevInfo == null)
        {
            LOG.warn("certificate with issuer='{}' and serialNumber={} does not exist",
                    caCert.getSubject(), serialNumber);
            return null;
        }

        CertRevocationInfo currentRevInfo = certWithRevInfo.getRevInfo();
        if(currentRevInfo == null)
        {
            throw new OperationException(ErrorCode.CERT_UNREVOKED,
                    "certificate is not revoked");
        }

        CRLReason currentReason = currentRevInfo.getReason();
        if(force == false)
        {
            if(currentReason != CRLReason.CERTIFICATE_HOLD)
            {
                throw new OperationException(ErrorCode.NOT_PERMITTED,
                        "could not unrevoke certificate revoked with reason " + currentReason.getDescription());
            }
        }

        final String sql =
                "UPDATE CERT" +
                " SET LAST_UPDATE=?, REVOKED=?, REV_TIME=?, REV_INV_TIME=?, REV_REASON=?" +
                " WHERE ID=?";
        PreparedStatement ps = borrowPreparedStatement(sql);

        int certId = certWithRevInfo.getCert().getCertId().intValue();
        try
        {
            int idx = 1;
            ps.setLong(idx++, new Date().getTime()/1000);
            setBoolean(ps, idx++, false);
            ps.setNull(idx++, Types.INTEGER);
            ps.setNull(idx++, Types.INTEGER);
            ps.setNull(idx++, Types.INTEGER);
            ps.setLong(idx++, certId);

            int count = ps.executeUpdate();
            if(count != 1)
            {
                String message;
                if(count > 1)
                {
                    message = count + " rows modified, but exactly one is expected";
                }
                else
                {
                    message = "no row is modified, but exactly one is expected";
                }
                throw new OperationException(ErrorCode.SYSTEM_FAILURE, message);
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, null);
        }

        if(publishToDeltaCRLCache)
        {
            Integer caId = getCaId(caCert); // must not be null
            publishToDeltaCRLCache(caId, certWithRevInfo.getCert().getCert().getSerialNumber());
        }

        return certWithRevInfo.getCert();
    }

    private void publishToDeltaCRLCache(
            final int caId,
            final BigInteger serialNumber)
    throws DataAccessException
    {
        final String sql = "INSERT INTO DELTACRL_CACHE (ID, CA_ID, SERIAL) VALUES (?, ?, ?)";

        PreparedStatement ps = null;

        try
        {
            long id = nextDccId();
            ps = borrowPreparedStatement(sql);
            int idx = 1;
            ps.setLong(idx++, id);
            ps.setInt(idx++, caId);
            ps.setLong(idx++, serialNumber.longValue());
            ps.executeUpdate();
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, null);
        }
    }

    X509CertWithDBCertId getCert(
            final X509CertWithDBCertId caCert,
            final BigInteger serialNumber)
    throws OperationException, DataAccessException
    {
        X509CertWithRevocationInfo certWithRevInfo = getCertWithRevocationInfo(caCert, serialNumber);
        if(certWithRevInfo == null)
        {
            return null;
        }
        return certWithRevInfo.getCert();
    }

    void removeCertificate(
            final X509CertWithDBCertId caCert,
            final BigInteger serialNumber)
    throws OperationException, DataAccessException
    {
        Integer caId = getCaId(caCert);
        if(caId == null)
        {
            return;
        }

        final String sql = "DELETE FROM CERT WHERE CA_ID=? AND SERIAL=?";
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setInt(idx++, caId.intValue());
            ps.setLong(idx++, serialNumber.longValue());

            int count = ps.executeUpdate();
            if(count != 1)
            {
                String message;
                if(count > 1)
                {
                    message = count + " rows modified, but exactly one is expected";
                }
                else
                {
                    message = "no row is modified, but exactly one is expected";
                }
                throw new OperationException(ErrorCode.SYSTEM_FAILURE, message);
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, null);
        }

    }

    Long getGreatestSerialNumber(
            final X509CertWithDBCertId caCert)
    throws DataAccessException, OperationException
    {
        ParamChecker.assertNotNull("caCert", caCert);

        Integer caId = getCaId(caCert);
        if(caId == null)
        {
            return null;
        }

        final String sql = "SELECT MAX(SERIAL) FROM CERT WHERE CA_ID=?";
        PreparedStatement ps = borrowPreparedStatement(sql);
        ResultSet rs = null;
        try
        {
            ps.setInt(1, caId);

            rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        } finally
        {
            releaseDbResources(ps, rs);
        }
    }

    List<Integer> getPublishQueueEntries(
            final X509CertWithDBCertId caCert,
            final String publisherName,
            final int numEntries)
    throws DataAccessException, OperationException
    {
        ParamChecker.assertNotNull("caCert", caCert);
        if(numEntries < 1)
        {
            throw new IllegalArgumentException("numEntries is not positive");
        }

        int caId = getCaId(caCert);
        int publisherId = getPublisherId(publisherName);

        final String sql = dataSource.createFetchFirstSelectSQL(
                "CERT_ID FROM PUBLISHQUEUE WHERE CA_ID=? AND PUBLISHER_ID=?", numEntries, "CERT_ID ASC");
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setInt(idx++, caId);
            ps.setLong(idx++, publisherId);
            rs = ps.executeQuery();

            List<Integer> ret = new ArrayList<>();
            while(rs.next() && ret.size() < numEntries)
            {
                int certId = rs.getInt("CERT_ID");
                if(ret.contains(certId) == false)
                {
                    ret.add(certId);
                }
            }

            return ret;
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    boolean containsCertificates(
            final X509CertWithDBCertId caCert,
            final boolean ee)
    throws DataAccessException, OperationException
    {
        ParamChecker.assertNotNull("caCert", caCert);
        final String sql = dataSource.createFetchFirstSelectSQL("COUNT(*) FROM CERT WHERE CA_ID=? AND EE=?", 1);
        int caId = getCaId(caCert);

        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setInt(idx++, caId);
            ps.setInt(2, ee ? 1 : 0);
            rs = ps.executeQuery();

            if(rs.next())
            {
                return rs.getInt(1) > 0;
            }
            else
            {
                return false;
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    List<BigInteger> getSerialNumbers(
            final X509CertWithDBCertId caCert,
            final Date notExpiredAt,
            final BigInteger startSerial,
            final int numEntries,
            final boolean onlyRevoked,
            final boolean onlyCACerts,
            final boolean onlyUserCerts)
    throws DataAccessException, OperationException
    {
        ParamChecker.assertNotNull("caCert", caCert);
        if(numEntries < 1)
        {
            throw new IllegalArgumentException("numEntries is not positive");
        }

        int caId = getCaId(caCert);

        StringBuilder sb = new StringBuilder("SERIAL FROM CERT WHERE CA_ID=? AND SERIAL>?");
        if(notExpiredAt != null)
        {
            sb.append(" AND NOTAFTER>?");
        }
        if(onlyRevoked)
        {
            sb.append(" AND REVOKED=1");
        }

        if(onlyCACerts)
        {
            sb.append(" AND EE=0");
        }
        else if(onlyUserCerts)
        {
            sb.append(" AND EE=1");
        }

        final String sql = dataSource.createFetchFirstSelectSQL(sb.toString(), numEntries, "SERIAL ASC");
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setInt(idx++, caId);
            ps.setLong(idx++, (startSerial == null)? 0 : startSerial.longValue()-1);
            if(notExpiredAt != null)
            {
                ps.setLong(idx++, notExpiredAt.getTime()/1000 + 1);
            }
            rs = ps.executeQuery();

            List<BigInteger> ret = new ArrayList<>();
            while(rs.next() && ret.size() < numEntries)
            {
                long serial = rs.getLong("SERIAL");
                ret.add(BigInteger.valueOf(serial));
            }

            return ret;
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    List<BigInteger> getExpiredSerialNumbers(
            final X509CertWithDBCertId caCert,
            final long expiredAt,
            final int numEntries,
            final String certprofile,
            String userLike)
    throws DataAccessException, OperationException
    {
        ParamChecker.assertNotNull("caCert", caCert);
        ParamChecker.assertNotNull("expiredAt", expiredAt);
        ParamChecker.assertNotBlank("certprofile", certprofile);

        if(numEntries < 1)
        {
            throw new IllegalArgumentException("numEntries is not positive");
        }

        int caId = getCaId(caCert);

        StringBuilder sqlBuilder = new StringBuilder(
                "SERIAL FROM CERT WHERE CA_ID=? AND NOTAFTER<? AND PROFILE_ID=?");

        if(userLike != null)
        {
            userLike = userLike.trim();
            if(StringUtil.isBlank(userLike) || "null".equalsIgnoreCase(userLike))
            {
                userLike = null;
            }
        }

        Integer certprofileId = certprofileStore.getId(certprofile);
        if(certprofileId == null)
        {
            return Collections.emptyList();
        }

        if(userLike == null)
        {
            sqlBuilder.append(" AND USER_ID IS NULL");
        }
        else if("all".equalsIgnoreCase(userLike) == false)
        {
            sqlBuilder.append(" AND USER_ID IN (SELECT ID FROM USERNAME WHERE NAME LIKE ?)");
        }

        final String sql = dataSource.createFetchFirstSelectSQL(sqlBuilder.toString(), numEntries);
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setInt(idx++, caId);
            ps.setLong(idx++, expiredAt);
            ps.setInt(idx++, certprofileId);

            if(userLike != null && "all".equalsIgnoreCase(userLike) == false)
            {
                ps.setString(idx++, userLike);
            }

            rs = ps.executeQuery();

            List<BigInteger> ret = new ArrayList<>();
            while(rs.next() && ret.size() < numEntries)
            {
                long serial = rs.getLong("SERIAL");
                ret.add(BigInteger.valueOf(serial));
            }

            return ret;
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    int getNumOfExpiredCerts(
            final X509CertWithDBCertId caCert,
            final long expiredAt,
            final String certprofile,
            String userLike)
    throws DataAccessException, OperationException
    {
        ParamChecker.assertNotNull("caCert", caCert);
        ParamChecker.assertNotNull("expiredAt", expiredAt);
        ParamChecker.assertNotBlank("certprofile", certprofile);

        int caId = getCaId(caCert);

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("SELECT COUNT(*) FROM CERT WHERE CA_ID=? AND NOTAFTER<? AND PROFILE_ID=?");
        if(userLike != null)
        {
            userLike = userLike.trim();
            if(StringUtil.isBlank(userLike) || "null".equalsIgnoreCase(userLike))
            {
                userLike = null;
            }
        }

        Integer certprofileId = certprofileStore.getId(certprofile);
        if(certprofileId == null)
        {
            return 0;
        }

        if(userLike == null)
        {
            sqlBuilder.append(" AND USER_ID IS NULL");
        }
        else if("all".equalsIgnoreCase(userLike) == false)
        {
            sqlBuilder.append(" AND USER_ID IN (SELECT ID FROM USERNAME WHERE NAME LIKE ?)");
        }

        String sql = sqlBuilder.toString();
        PreparedStatement ps = borrowPreparedStatement(sql);

        ResultSet rs = null;
        try
        {
            int idx = 1;
            ps.setInt(idx++, caId);
            ps.setLong(idx++, expiredAt);
            ps.setInt(idx++, certprofileId);

            if(userLike != null && "all".equalsIgnoreCase(userLike) == false)
            {
                ps.setString(idx++, userLike);
            }

            rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    byte[] getEncodedCRL(
            final X509CertWithDBCertId caCert,
            final BigInteger crlNumber)
    throws DataAccessException, OperationException
    {
        ParamChecker.assertNotNull("caCert", caCert);

        Integer caId = getCaId(caCert);
        if(caId == null)
        {
            return null;
        }

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("THISUPDATE, CRL FROM CRL WHERE CA_ID=?");
        if(crlNumber != null)
        {
            sqlBuilder.append(" AND CRL_NO=?");
        }

        String sql = dataSource.createFetchFirstSelectSQL(sqlBuilder.toString(), 1, "THISUPDATE DESC");
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setInt(idx++, caId.intValue());
            if(crlNumber != null)
            {
                ps.setLong(idx++, crlNumber.longValue());
            }

            rs = ps.executeQuery();

            byte[] encodedCrl = null;

            long current_thisUpdate = 0;
            // iterate all entries to make sure that the latest CRL will be returned
            while(rs.next())
            {
                long thisUpdate = rs.getLong("THISUPDATE");
                if(thisUpdate >= current_thisUpdate)
                {
                    String b64Crl = rs.getString("CRL");
                    encodedCrl = Base64.decode(b64Crl);
                    current_thisUpdate = thisUpdate;
                }
            }

            return encodedCrl;
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    int cleanupCRLs(
            final X509CertWithDBCertId caCert,
            final int numCRLs)
    throws DataAccessException, OperationException
    {
        if(numCRLs < 1)
        {
            throw new IllegalArgumentException("numCRLs is not positive");
        }

        ParamChecker.assertNotNull("caCert", caCert);
        Integer caId = getCaId(caCert);
        if(caId == null)
        {
            return 0;
        }

        String sql = "SELECT CRL_NO FROM CRL WHERE CA_ID=? AND DELTACRL=?";
        PreparedStatement ps = borrowPreparedStatement(sql);

        List<Integer> crlNumbers = new LinkedList<>();

        ResultSet rs = null;
        try
        {
            int idx = 1;
            ps.setInt(idx++, caId.intValue());
            ps.setBoolean(idx++, false);
            rs = ps.executeQuery();

            while(rs.next())
            {
                int crlNumber = rs.getInt("CRL_NO");
                crlNumbers.add(crlNumber);
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }

        int n = crlNumbers.size();
        Collections.sort(crlNumbers);

        int numCrlsToDelete = n - numCRLs;
        if(numCrlsToDelete < 1)
        {
            return 0;
        }

        int crlNumber = crlNumbers.get(numCrlsToDelete - 1);
        sql = "DELETE FROM CRL WHERE CA_ID=? AND CRL_NO<?";
        ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setInt(idx++, caId.intValue());
            ps.setInt(idx++, crlNumber + 1);
            ps.executeUpdate();
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, null);
        }

        return numCrlsToDelete;
    }

    X509CertificateInfo getCertForId(
            final X509CertWithDBCertId caCert,
            final int certId)
    throws DataAccessException, OperationException, CertificateException
    {
        ParamChecker.assertNotNull("caCert", caCert);

        StringBuilder m = new StringBuilder();
        m.append("T1.PROFILE_ID PROFILE_ID, T1.REVOKED REVOKED, T1.REV_REASON REV_REASON, ");
        m.append("T1.REV_TIME REV_TIME, T1.REV_INV_TIME REV_INV_TIME, T2.CERT CERT");
        m.append(" FROM CERT T1, RAWCERT T2 WHERE T1.ID=? AND T2.CERT_ID=T1.ID");

        String sql = dataSource.createFetchFirstSelectSQL(m.toString(), 1);
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            ps.setInt(1, certId);
            rs = ps.executeQuery();

            if(rs.next())
            {
                String b64Cert = rs.getString("CERT");
                byte[] encodedCert = Base64.decode(b64Cert);
                X509Certificate cert = X509Util.parseCert(encodedCert);

                int certprofile_id = rs.getInt("PROFILE_ID");
                String certprofileName = certprofileStore.getName(certprofile_id);

                X509CertWithDBCertId certWithMeta = new X509CertWithDBCertId(cert, encodedCert);

                X509CertificateInfo certInfo = new X509CertificateInfo(certWithMeta,
                        caCert, cert.getPublicKey().getEncoded(), certprofileName);

                boolean revoked = rs.getBoolean("REVOKED");
                if(revoked == false)
                {
                    return certInfo;
                }

                int rev_reasonCode = rs.getInt("REV_REASON");
                CRLReason rev_reason = CRLReason.forReasonCode(rev_reasonCode);
                long rev_time = rs.getLong("REV_TIME");
                long invalidity_time = rs.getLong("REV_INV_TIME");

                Date invalidityTime = (invalidity_time == 0 || invalidity_time == rev_time) ?
                        null : new Date(invalidity_time * 1000);
                CertRevocationInfo revInfo = new CertRevocationInfo(rev_reason,
                        new Date(rev_time * 1000), invalidityTime);
                certInfo.setRevocationInfo(revInfo);
                return certInfo;
            }
        } catch (IOException e)
        {
            throw new OperationException(ErrorCode.SYSTEM_FAILURE, "IOException: " + e.getMessage());
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }

        return null;
    }

    X509CertWithDBCertId getCertForId(
            final int certId)
    throws DataAccessException, OperationException
    {
        final String sql = dataSource.createFetchFirstSelectSQL("CERT FROM RAWCERT WHERE CERT_ID=?", 1);
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            ps.setInt(1, certId);
            rs = ps.executeQuery();

            if(rs.next())
            {
                String b64Cert = rs.getString("CERT");
                if(b64Cert == null)
                {
                    return null;
                }

                byte[] encodedCert = Base64.decode(b64Cert);
                X509Certificate cert;
                try
                {
                    cert = X509Util.parseCert(encodedCert);
                } catch (CertificateException e)
                {
                    throw new OperationException(ErrorCode.SYSTEM_FAILURE, "CertificateException: " + e.getMessage());
                } catch (IOException e)
                {
                    throw new OperationException(ErrorCode.SYSTEM_FAILURE, "IOException: " + e.getMessage());
                }
                return new X509CertWithDBCertId(cert, encodedCert);
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }

        return null;
    }

    X509CertWithRevocationInfo getCertWithRevocationInfo(
            final X509CertWithDBCertId caCert,
            final BigInteger serial)
    throws DataAccessException, OperationException
    {
        ParamChecker.assertNotNull("caCert", caCert);
        ParamChecker.assertNotNull("serial", serial);

        Integer caId = getCaId(caCert);
        if(caId == null)
        {
            return null;
        }

        String sql = "T1.ID ID, T1.REVOKED REVOKED, T1.REV_REASON REV_REASON, T1.REV_TIME REV_TIME," +
                " T1.REV_INV_TIME REV_INV_TIME, T1.PROFILE_ID PROFILE_ID," +
                " T2.CERT CERT FROM CERT T1, RAWCERT T2" +
                " WHERE T1.CA_ID=? AND T1.SERIAL=? AND T2.CERT_ID=T1.ID";

        sql = dataSource.createFetchFirstSelectSQL(sql, 1);
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setInt(idx++, caId.intValue());
            ps.setLong(idx++, serial.longValue());
            rs = ps.executeQuery();

            if(rs.next())
            {
                int certId = rs.getInt("ID");
                String b64Cert = rs.getString("CERT");
                byte[] certBytes = (b64Cert == null) ? null : Base64.decode(b64Cert);
                X509Certificate cert;
                try
                {
                    cert = X509Util.parseCert(certBytes);
                } catch (CertificateException | IOException e)
                {
                    throw new OperationException(ErrorCode.SYSTEM_FAILURE, e.getClass().getName() + ": " + e.getMessage());
                }

                CertRevocationInfo revInfo = null;
                boolean revoked = rs.getBoolean("REVOKED");
                if(revoked)
                {
                    int rev_reason = rs.getInt("REV_REASON");
                    long rev_time = rs.getLong("REV_TIME");
                    long rev_invalidity_time = rs.getLong("REV_INV_TIME");
                    Date invalidityTime = rev_invalidity_time == 0 ? null : new Date(1000 * rev_invalidity_time);
                    revInfo = new CertRevocationInfo(CRLReason.forReasonCode(rev_reason),
                            new Date(1000 * rev_time),
                            invalidityTime);
                }

                X509CertWithDBCertId certWithMeta = new X509CertWithDBCertId(cert, certBytes);
                certWithMeta.setCertId(certId);

                int certprofileId = rs.getInt("PROFILE_ID");
                String profileName = certprofileStore.getName(certprofileId);
                X509CertWithRevocationInfo ret = new X509CertWithRevocationInfo();
                ret.setCertprofile(profileName);
                ret.setCert(certWithMeta);
                ret.setRevInfo(revInfo);
                return ret;
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, null);
        }

        return null;
    }

    X509CertificateInfo getCertificateInfo(
            final X509CertWithDBCertId caCert,
            final BigInteger serial)
    throws DataAccessException, OperationException, CertificateException
    {
        ParamChecker.assertNotNull("caCert", caCert);
        ParamChecker.assertNotNull("serial", serial);

        Integer caId = getCaId(caCert);
        if(caId == null)
        {
            return null;
        }

        StringBuilder m = new StringBuilder(200);
        m.append("T1.PROFILE_ID PROFILE_ID, T1.REVOKED REVOKED, T1.REV_REASON REV_REASON,");
        m.append("T1.REV_TIME REV_TIME, T1.REV_INV_TIME REV_INV_TIME, T2.CERT CERT");
        m.append(" FROM CERT T1, RAWCERT T2");
        m.append(" WHERE T1.CA_ID=? AND T1.SERIAL=? AND T2.CERT_ID=T1.ID");

        final String sql = dataSource.createFetchFirstSelectSQL(m.toString(), 1);
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setInt(idx++, caId.intValue());
            ps.setLong(idx++, serial.longValue());
            rs = ps.executeQuery();

            if(rs.next())
            {
                String b64Cert = rs.getString("CERT");
                byte[] encodedCert = Base64.decode(b64Cert);
                X509Certificate cert = X509Util.parseCert(encodedCert);

                int certprofile_id = rs.getInt("PROFILE_ID");
                String certprofileName = certprofileStore.getName(certprofile_id);

                X509CertWithDBCertId certWithMeta = new X509CertWithDBCertId(cert, encodedCert);

                byte[] subjectPublicKeyInfo = Certificate.getInstance(encodedCert).getTBSCertificate()
                        .getSubjectPublicKeyInfo().getEncoded();
                X509CertificateInfo certInfo = new X509CertificateInfo(certWithMeta,
                        caCert, subjectPublicKeyInfo, certprofileName);

                boolean revoked = rs.getBoolean("REVOKED");
                if(revoked == false)
                {
                    return certInfo;
                }

                int rev_reasonCode = rs.getInt("REV_REASON");
                CRLReason rev_reason = CRLReason.forReasonCode(rev_reasonCode);
                long rev_time = rs.getLong("REV_TIME");
                long invalidity_time = rs.getLong("REV_INV_TIME");

                Date invalidityTime = invalidity_time == 0 ? null : new Date(invalidity_time * 1000);
                CertRevocationInfo revInfo = new CertRevocationInfo(rev_reason,
                        new Date(rev_time * 1000), invalidityTime);
                certInfo.setRevocationInfo(revInfo);
                return certInfo;
            }
        } catch (IOException e)
        {
            LOG.warn("getCertificateInfo()", e);
            throw new OperationException(ErrorCode.SYSTEM_FAILURE, "IOException: " + e.getMessage());
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }

        return null;
    }

    List<CertRevInfoWithSerial> getRevokedCertificates(
            final X509CertWithDBCertId caCert,
            final Date notExpiredAt,
            final BigInteger startSerial,
            final int numEntries,
            final boolean onlyCACerts,
            final boolean onlyUserCerts)
    throws DataAccessException, OperationException
    {
        ParamChecker.assertNotNull("caCert", caCert);
        ParamChecker.assertNotNull("notExpiredAt", notExpiredAt);

        if(numEntries < 1)
        {
            throw new IllegalArgumentException("numEntries is not positive");
        }

        Integer caId = getCaId(caCert);
        if(caId == null)
        {
            return Collections.emptyList();
        }

        StringBuilder sqlBuiler = new StringBuilder();
        sqlBuiler.append("SERIAL, REV_REASON, REV_TIME, REV_INV_TIME FROM CERT");
        sqlBuiler.append(" WHERE CA_ID=? AND REVOKED=? AND SERIAL>? AND NOTAFTER>?");
        if(onlyCACerts)
        {
            sqlBuiler.append(" AND EE=0");
        }
        else if(onlyUserCerts)
        {
            sqlBuiler.append(" AND EE=1");
        }

        String sql = dataSource.createFetchFirstSelectSQL(sqlBuiler.toString(), numEntries, "SERIAL ASC");
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setInt(idx++, caId.intValue());
            setBoolean(ps, idx++, true);
            ps.setLong(idx++, startSerial.longValue() - 1);
            ps.setLong(idx++, notExpiredAt.getTime() / 1000 + 1);
            rs = ps.executeQuery();

            List<CertRevInfoWithSerial> ret = new ArrayList<>();
            while(rs.next())
            {
                long serial = rs.getLong("SERIAL");
                int rev_reason = rs.getInt("REV_REASON");
                long rev_time = rs.getLong("REV_TIME");
                long rev_invalidity_time = rs.getLong("REV_INV_TIME");

                Date invalidityTime = rev_invalidity_time == 0 ? null :  new Date(1000 * rev_invalidity_time);
                CertRevInfoWithSerial revInfo = new CertRevInfoWithSerial(
                        BigInteger.valueOf(serial),
                        rev_reason, new Date(1000 * rev_time), invalidityTime);
                ret.add(revInfo);
            }

            return ret;
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    List<CertRevInfoWithSerial> getCertificatesForDeltaCRL(
            final X509CertWithDBCertId caCert,
            final BigInteger startSerial,
            final int numEntries,
            final boolean onlyCACerts,
            final boolean onlyUserCerts)
    throws DataAccessException, OperationException
    {
        ParamChecker.assertNotNull("caCert", caCert);

        if(numEntries < 1)
        {
            throw new IllegalArgumentException("numEntries is not positive");
        }

        Integer caId = getCaId(caCert);
        if(caId == null)
        {
            return Collections.emptyList();
        }

        String sql = dataSource.createFetchFirstSelectSQL(
                "SERIAL FROM DELTACRL_CACHE WHERE CA_ID=? AND SERIAL>?", numEntries, "SERIAL ASC");
        List<Long> serials = new LinkedList<>();
        ResultSet rs = null;

        PreparedStatement ps = borrowPreparedStatement(sql);
        try
        {
            int idx = 1;
            ps.setInt(idx++, caId.intValue());
            ps.setLong(idx++, startSerial.longValue() - 1);
            rs = ps.executeQuery();

            while(rs.next())
            {
                long serial = rs.getLong("SERIAL");
                serials.add(serial);
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }

        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("REVOKED, REV_REASON, REV_TIME, REV_INV_TIME");
        sqlBuilder.append(" FROM CERT WHERE CA_ID=? AND SERIAL=?");
        if(onlyCACerts)
        {
            sqlBuilder.append(" AND EE=0");
        }
        else if(onlyUserCerts)
        {
            sqlBuilder.append(" AND EE=1");
        }

        sql = dataSource.createFetchFirstSelectSQL(sqlBuilder.toString(), 1);
        ps = borrowPreparedStatement(sql);

        List<CertRevInfoWithSerial> ret = new ArrayList<>();
        for(Long serial : serials)
        {
            try
            {
                ps.setInt(1, caId);
                ps.setLong(2, serial);
                rs = ps.executeQuery();

                if(rs.next() == false)
                {
                    continue;
                }
                CertRevInfoWithSerial revInfo;

                boolean revoked = rs.getBoolean("REVOEKD");
                if(revoked)
                {
                    int rev_reason = rs.getInt("REV_REASON");
                    long rev_time = rs.getLong("REV_TIME");
                    long rev_invalidity_time = rs.getLong("REV_INV_TIME");

                    Date invalidityTime = rev_invalidity_time == 0 ? null :  new Date(1000 * rev_invalidity_time);
                    revInfo = new CertRevInfoWithSerial(
                            BigInteger.valueOf(serial),
                            rev_reason, new Date(1000 * rev_time), invalidityTime);
                }
                else
                {
                    long lastUpdate = rs.getLong("LAST_UPDATE");
                    revInfo = new CertRevInfoWithSerial(BigInteger.valueOf(serial),
                            CRLReason.REMOVE_FROM_CRL.getCode(), new Date(1000 * lastUpdate), null);
                }
                ret.add(revInfo);
            }catch(SQLException e)
            {
                throw dataSource.translate(sql, e);
            }finally
            {
                releaseDbResources(null, rs);
            }
        }

        return ret;
    }

    CertStatus getCertStatusForSubject(
            final X509CertWithDBCertId caCert,
            final X500Principal subject)
    throws DataAccessException
    {
        String subjectFp = X509Util.sha1sum_canonicalized_name(subject);
        return getCertStatusForSubjectFp(caCert, subjectFp);
    }

    CertStatus getCertStatusForSubject(
            final X509CertWithDBCertId caCert,
            final X500Name subject)
    throws DataAccessException
    {
        String subjectFp = X509Util.sha1sum_canonicalized_name(subject);
        return getCertStatusForSubjectFp(caCert, subjectFp);
    }

    private CertStatus getCertStatusForSubjectFp(
            final X509CertWithDBCertId caCert,
            final String subjectFp)
    throws DataAccessException
    {
        byte[] encodedCert = caCert.getEncodedCert();
        Integer caId =  caInfoStore.getCaIdForCert(encodedCert);
        if(caId == null)
        {
            return CertStatus.Unknown;
        }

        final String sql = dataSource.createFetchFirstSelectSQL(
                "REVOKED FROM CERT WHERE FP_SUBJECT=? AND CA_ID=?", 1);
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setString(idx++, subjectFp);
            ps.setInt(idx++, caId);

            rs = ps.executeQuery();
            if(rs.next())
            {
                return rs.getBoolean("REVOKED") ? CertStatus.Revoked : CertStatus.Good;
            }
            else
            {
                return CertStatus.Unknown;
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    boolean certIssuedForSubject(
            final X509CertWithDBCertId caCert,
            final String sha1FpSubject)
    throws OperationException, DataAccessException
    {
        byte[] encodedCert = caCert.getEncodedCert();
        Integer caId =  caInfoStore.getCaIdForCert(encodedCert);

        if(caId == null)
        {
            return false;
        }

        final String sql = dataSource.createFetchFirstSelectSQL(
                "COUNT(ID) FROM CERT WHERE FP_SUBJECT=? AND CA_ID=?", 1);

        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setString(idx++, sha1FpSubject);
            ps.setInt(idx++, caId);

            rs = ps.executeQuery();
            if(rs.next())
            {
                return rs.getInt(1) > 0;
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }

        return false;
    }

    SubjectKeyProfileBundle getLatestCert(
            final X509CertWithDBCertId caCert,
            final String subjectFp,
            final String keyFp,
            final String profile)
    throws DataAccessException
    {
        byte[] encodedCert = caCert.getEncodedCert();
        Integer caId =  caInfoStore.getCaIdForCert(encodedCert);

        if(caId == null)
        {
            return null;
        }

        Integer profileId = certprofileStore.getId(profile);
        if(profileId == null)
        {
            return null;
        }

        String sql =
                "ID, REVOKED FROM CERT WHERE FP_PK=? AND FP_SUBJECT=? AND CA_ID=? AND PROFILE_ID=?";
        sql = dataSource.createFetchFirstSelectSQL(sql, 1, "ID DESC");
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setString(idx++, keyFp);
            ps.setString(idx++, subjectFp);
            ps.setInt(idx++, caId);
            ps.setInt(idx++, profileId);

            rs = ps.executeQuery();

            if(rs.next() == false)
            {
                return null;
            }

            int id = rs.getInt("ID");
            boolean revoked = rs.getBoolean("REVOKED");
            return new SubjectKeyProfileBundle(id, subjectFp, keyFp, profile, revoked);
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    boolean isCertForSubjectIssued(
            final X509CertWithDBCertId caCert,
            final String subjectFp,
            final String profile)
    throws DataAccessException
    {
        return isCertIssuedForFp("FP_SUBJECT", caCert, subjectFp, profile);
    }

    boolean isCertForKeyIssued(
            final X509CertWithDBCertId caCert,
            final String keyFp,
            final String profile)
    throws DataAccessException
    {
        return isCertIssuedForFp("FP_PK", caCert, keyFp, profile);
    }

    private boolean isCertIssuedForFp(
            final String fpColumnName,
            final X509CertWithDBCertId caCert,
            final String fp,
            final String profile)
    throws DataAccessException
    {
        byte[] encodedCert = caCert.getEncodedCert();
        Integer caId =  caInfoStore.getCaIdForCert(encodedCert);

        if(caId == null)
        {
            return false;
        }

        Integer profileId = null;
        if(profile != null)
        {
            profileId = certprofileStore.getId(profile);
            if(profileId == null)
            {
                return false;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ID FROM CERT WHERE ").append(fpColumnName).append("=?");
        sb.append(" AND CA_ID=?");
        if(profile != null)
        {
            sb.append(" AND PROFILE_ID=?");
        }
        String sql = dataSource.createFetchFirstSelectSQL(sb.toString(), 1);
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setString(idx++, fp);
            ps.setInt(idx++, caId);
            if(profile != null)
            {
                ps.setInt(idx++, profileId);
            }

            rs = ps.executeQuery();

            return rs.next();
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    private String fp(
            final byte[] data)
    {
        return SecurityUtil.sha1sum(data);
    }

    private int getCaId(
            final X509CertWithDBCertId caCert)
    throws OperationException
    {
        byte[] encodedCert = caCert.getEncodedCert();
        Integer id =  caInfoStore.getCaIdForCert(encodedCert);
        if(id == null)
        {
            throw new IllegalStateException("could not find CA with subject  '" + caCert.getSubject() + "' in table " +
                    caInfoStore.getTable() + ", please start XiPKI in master mode first the restart this XiPKI system");
        }
        return id.intValue();
    }

    void addCa(
            final X509CertWithDBCertId caCert)
    throws DataAccessException, OperationException
    {
        byte[] encodedCert = caCert.getEncodedCert();
        if(caInfoStore.getCaIdForCert(encodedCert) != null)
        {
            return;
        }

        String hexSha1Fp = fp(encodedCert);

        String tblName = caInfoStore.getTable();
        long maxId = dataSource.getMax(null, tblName, "ID");
        int id = (int) maxId + 1;

        final String sql = new StringBuilder("INSERT INTO ").append(tblName)
                .append(" (ID, SUBJECT, FP_CERT, CERT)").append(" VALUES (?, ?, ?, ?)").toString();
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            String b64Cert = Base64.toBase64String(encodedCert);
            String subject = caCert.getSubject();
            int idx = 1;
            ps.setInt(idx++, id);
            ps.setString(idx++, subject);
            ps.setString(idx++, hexSha1Fp);
            ps.setString(idx++, b64Cert);

            ps.execute();

            CertBasedIdentityEntry newInfo = new CertBasedIdentityEntry(id, subject, hexSha1Fp, b64Cert);
            caInfoStore.addIdentityEntry(newInfo);
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        } finally
        {
            releaseDbResources(ps, null);
        }
    }

    private int getUserId(
            final String user)
    throws DataAccessException
    {
        Integer id = usernameIdCache.get(user);
        if(id != null)
        {
            return id.intValue();
        }

        id = executeGetUserIdSql(user);

        if(id == null)
        {
            int i = 0;
            final int tries = 5;
            for(; i < tries; i++)
            {
                int tmpId = (i == 0) ? user.hashCode() : random.nextInt();
                try
                {
                    executeAddUserSql(user, tmpId);
                    id = tmpId;
                    break;
                }catch(DataAccessException e)
                {
                    Integer id2 = executeGetUserIdSql(user);
                    if(id2 != null)
                    {
                        id = id2.intValue();
                        break;
                    }

                    if(e instanceof DuplicateKeyException && i < tries - 1)
                    {
                        continue;
                    }
                    else
                    {
                        throw e;
                    }
                }
            }

            if(id != null && i > 0)
            {
                LOG.debug("datasource {} added user {} after {} tries",
                        dataSource.getDatasourceName(), user, i + 1);
            }
        }

        if(id == null)
        {
            throw new RuntimeException("userId is null, this should not happen");
        }
        usernameIdCache.put(user, id);
        return id;
    }

    private Integer executeGetUserIdSql(
            final String user)
    throws DataAccessException
    {
        final String sql = dataSource.createFetchFirstSelectSQL("ID FROM USERNAME WHERE NAME=?", 1);
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);
        try
        {
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
            throw dataSource.translate(sql, e);
        } finally
        {
            dataSource.releaseResources(ps, rs);
        }
    }

    private void executeAddUserSql(
            final String user,
            final int userId)
    throws DataAccessException
    {
        final String sql = "INSERT INTO USERNAME (ID, NAME) VALUES (?, ?)";
        PreparedStatement ps = borrowPreparedStatement(sql);
        try
        {
            ps.setInt(1, userId);
            ps.setString(2, user);
            ps.executeUpdate();
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        } finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    private int getRequestorId(
            final String name)
    {
        return getIdForName(name, requestorInfoStore);
    }

    void addRequestorName(
            final String name)
    throws DataAccessException
    {
        addName(name, requestorInfoStore);
    }

    private int getPublisherId(
            final String name)
    {
        return getIdForName(name, publisherStore);
    }

    void addPublisherName(
            final String name)
    throws DataAccessException
    {
        addName(name, publisherStore);
    }

    private int getCertprofileId(
            final String name)
    {
        return getIdForName(name, certprofileStore);
    }

    void addCertprofileName(
            final String name)
    throws DataAccessException
    {
        addName(name, certprofileStore);
    }

    private int getIdForName(
            final String name,
            final NameIdStore store)
    {
        Integer id = store.getId(name);
        if(id == null)
        {
            throw new IllegalStateException("could not find entry named " + name + " in table " +
                    store.getTable() + ", please start XiPKI in master mode first and then restart this XiPKI system");
        }
        return id.intValue();
    }

    private void addName(
            final String name,
            final NameIdStore store)
    throws DataAccessException
    {
        if(store.getId(name) != null)
        {
            return;
        }

        String tblName = store.getTable();
        long maxId = dataSource.getMax(null, tblName, "ID");
        int id = (int) maxId + 1;

        final String sql = new StringBuilder("INSERT INTO ").append(tblName).append(" (ID, NAME) VALUES (?, ?)").toString();
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setInt(idx++, id);
            ps.setString(idx++, name);

            ps.execute();
            store.addEntry(name, id);
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, null);
        }
    }

    private PreparedStatement[] borrowPreparedStatements(
            final String... sqlQueries)
    throws DataAccessException
    {
        Connection c = dataSource.getConnection();
        if(c == null)
        {
            throw new DataAccessException("could not get connection");
        }

        final int n = sqlQueries.length;
        PreparedStatement[] pss = new PreparedStatement[n];
        for(int i = 0; i < n; i++)
        {
            pss[i] = dataSource.prepareStatement(c, sqlQueries[i]);
            if(pss[i] != null)
            {
                continue;
            }

            // destroy all already initialized statements
            for(int j = 0; j < i; j++)
            {
                try
                {
                    pss[j].close();
                }catch(Throwable t)
                {
                    LOG.warn("could not close preparedStatement", t);
                }
            }

            try
            {
                c.close();
            }catch(Throwable t)
            {
                LOG.warn("could not close connection", t);
            }

            throw new DataAccessException("could not create prepared statement for " + sqlQueries[i]);
        }

        return pss;
    }

    private PreparedStatement borrowPreparedStatement(
            final String sqlQuery)
    throws DataAccessException
    {
        PreparedStatement ps = null;
        Connection c = dataSource.getConnection();
        if(c != null)
        {
            ps = dataSource.prepareStatement(c, sqlQuery);
        }

        if(ps == null)
        {
            throw new DataAccessException("could not create prepared statement for " + sqlQuery);
        }

        return ps;
    }

    private void releaseDbResources(
            final Statement ps,
            final ResultSet rs)
    {
        dataSource.releaseResources(ps, rs);
    }

    boolean isHealthy()
    {
        final String sql = "SELECT ID FROM CS_CA";

        try
        {
            PreparedStatement ps = borrowPreparedStatement(sql);

            ResultSet rs = null;
            try
            {
                rs = ps.executeQuery();
            }finally
            {
                releaseDbResources(ps, rs);
            }
            return true;
        }catch(Exception e)
        {
            LOG.error("isHealthy(). {}: {}", e.getClass().getName(), e.getMessage());
            LOG.debug("isHealthy()", e);
            return false;
        }
    }

    String getLatestSN(
            final X500Name nameWithSN)
    throws OperationException
    {
        RDN[] rdns1 = nameWithSN.getRDNs();
        RDN[] rdns2 = new RDN[rdns1.length];
        for(int i = 0; i < rdns1.length; i++)
        {
            RDN rdn = rdns1[i];
            if(rdn.getFirst().getType().equals(ObjectIdentifiers.DN_SERIALNUMBER))
            {
                rdns2[i] = new RDN(ObjectIdentifiers.DN_SERIALNUMBER, new DERPrintableString("%"));
            }
            else
            {
                rdns2[i] = rdn;
            }
        }

        String namePattern = X509Util.getRFC4519Name(new X500Name(rdns2));

        final String sql = dataSource.createFetchFirstSelectSQL(
                "SUBJECT FROM CERT WHERE SUBJECT LIKE ?", 1, "NOTBEFORE DESC");
        ResultSet rs = null;
        PreparedStatement ps;
        try
        {
            ps = borrowPreparedStatement(sql);
        } catch (DataAccessException e)
        {
            throw new OperationException(ErrorCode.DATABASE_FAILURE, e.getMessage());
        }

        try
        {
            ps.setString(1, namePattern);
            rs = ps.executeQuery();
            if(rs.next())
            {
                String str = rs.getString("SUBJECT");
                X500Name lastName = new X500Name(str);
                RDN[] rdns = lastName.getRDNs(ObjectIdentifiers.DN_SERIALNUMBER);
                if(rdns == null || rdns.length == 0)
                {
                    return null;
                }
                else
                {
                    return X509Util.rdnValueToString(rdns[0].getFirst().getValue());
                }
            }
        }catch(SQLException e)
        {
            throw new OperationException(ErrorCode.DATABASE_FAILURE, e.getMessage());
        }finally
        {
            releaseDbResources(ps, rs);
        }

        return null;
    }

    Long getNotBeforeOfFirstCertStartsWithCN(
            final String commonName,
            final String profileName)
    throws DataAccessException
    {
        Integer profileId = certprofileStore.getId(profileName);
        if(profileId == null)
        {
            return null;
        }

        final String sql = dataSource.createFetchFirstSelectSQL(
                "NOTBEFORE FROM CERT WHERE PROFILE_ID=? AND SUBJECT LIKE ?", 1, "NOTBEFORE ASC");
        ResultSet rs = null;
        PreparedStatement ps = borrowPreparedStatement(sql);

        try
        {
            int idx = 1;
            ps.setInt(idx++, profileId.intValue());
            ps.setString(idx++, "%cn=" + commonName + "%");

            rs = ps.executeQuery();

            if(rs.next() == false)
            {
                return null;
            }

            long notBefore = rs.getLong("NOTBEFORE");

            return notBefore == 0 ? null : notBefore;
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            releaseDbResources(ps, rs);
        }
    }

    void markMaxSerial(
            final X509CertWithDBCertId caCert,
            final String seqName)
    throws DataAccessException
    {
        byte[] encodedCert = caCert.getEncodedCert();
        Integer caId =  caInfoStore.getCaIdForCert(encodedCert);
        if(caId == null)
        {
            return;
        }

        final String sql = "SELECT MAX(SERIAL) FROM CERT WHERE CA_ID=?";
        Long maxSerial = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try
        {
            ps = borrowPreparedStatement(sql);
            ps.setInt(1, caId);
            rs = ps.executeQuery();
            if(rs.next())
            {
                maxSerial = rs.getLong(1);
            } else
            {
                return;
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            dataSource.releaseResources(ps, rs);
        }

        if(maxSerial != null)
        {
            dataSource.setLastUsedSeqValue(seqName, maxSerial);
        }
    }

    void commitNextSerialIfLess(
            final String caName,
            final long nextSerial)
    throws DataAccessException
    {
        Connection conn = dataSource.getConnection();
        PreparedStatement ps = null;
        try
        {
            String sql = "SELECT NEXT_SERIAL FROM CA WHERE NAME = '" + caName + "'";
            ResultSet rs = null;
            long nextSerialInDB;

            try
            {
                ps = conn.prepareStatement(sql);
                rs = ps.executeQuery();
                rs.next();
                nextSerialInDB = rs.getLong("NEXT_SERIAL");
            }catch(SQLException e)
            {
                throw dataSource.translate(sql, e);
            }finally
            {
                try
                {
                    ps.close();
                }catch(SQLException e)
                {
                }

                if(rs != null)
                {
                    try
                    {
                        rs.close();
                    }catch(SQLException e)
                    {
                    }
                }
            }

            if(nextSerialInDB < nextSerial)
            {
                sql = "UPDATE CA SET NEXT_SERIAL=? WHERE NAME=?";
                try
                {
                    ps = conn.prepareStatement(sql);
                    ps.setLong(1, nextSerial);
                    ps.setString(2, caName);
                    ps.executeUpdate();
                }catch(SQLException e)
                {
                    throw dataSource.translate(sql, e);
                }
            }
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    void commitNextCrlNoIfLess(
            final String caName,
            final int nextCrlNo)
    throws DataAccessException
    {
        Connection conn = dataSource.getConnection();
        PreparedStatement ps = null;
        try
        {
            final String sql = new StringBuilder("SELECT NEXT_CRLNO FROM CA WHERE NAME = '")
                .append(caName).append("'").toString();
            ResultSet rs = null;
            int nextCrlNoInDB;

            try
            {
                ps = conn.prepareStatement(sql);
                rs = ps.executeQuery();
                rs.next();
                nextCrlNoInDB = rs.getInt("NEXT_CRLNO");
            }catch(SQLException e)
            {
                throw dataSource.translate(sql, e);
            }finally
            {
                try
                {
                    ps.close();
                }catch(SQLException e)
                {
                }

                if(rs != null)
                {
                    try
                    {
                        rs.close();
                    }catch(SQLException e)
                    {
                    }
                }
            }

            if(nextCrlNoInDB < nextCrlNo)
            {
                String updateSql = "UPDATE CA SET NEXT_CRLNO=? WHERE NAME=?";
                try
                {
                    ps = conn.prepareStatement(updateSql);
                    ps.setInt(1, nextCrlNo);
                    ps.setString(2, caName);
                    ps.executeUpdate();
                }catch(SQLException e)
                {
                    throw dataSource.translate(updateSql, e);
                }
            }
        }finally
        {
            dataSource.releaseResources(ps, null);
        }
    }

    long nextSerial(
            final X509CertWithDBCertId caCert,
            final String seqName)
    throws DataAccessException
    {
        Connection conn = dataSource.getConnection();
        try
        {
            while(true)
            {
                long serial = dataSource.nextSeqValue(conn, seqName);
                if(certExists(caCert, serial) == false)
                {
                    return serial;
                }
            }
        } finally
        {
            dataSource.returnConnection(conn);
        }
    }

    private int nextCertId()
    throws DataAccessException
    {
        Connection conn = dataSource.getConnection();
        try
        {
            while(true)
            {
                int certId = (int) dataSource.nextSeqValue(conn, "CERT_ID");
                if(dataSource.columnExists(conn, "CERT", "ID", certId) == false)
                {
                    return certId;
                }
            }
        } finally
        {
            dataSource.returnConnection(conn);
        }
    }

    private long nextDccId()
    throws DataAccessException
    {
        Connection conn = dataSource.getConnection();
        try
        {
            while(true)
            {
                long id = dataSource.nextSeqValue(conn, "DCC_ID");
                if(dataSource.columnExists(conn, "DELTACRL_CACHE", "ID", id) == false)
                {
                    return id;
                }
            }
        } finally
        {
            dataSource.returnConnection(conn);
        }
    }

    private boolean certExists(
            final X509CertWithDBCertId caCert,
            final long serial)
    throws DataAccessException
    {
        byte[] encodedCert = caCert.getEncodedCert();
        Integer caId =  caInfoStore.getCaIdForCert(encodedCert);
        if(caId == null)
        {
            return false;
        }

        final String sql = "SELECT COUNT(*) FROM CERT WHERE CA_ID=? AND SERIAL=?";
        PreparedStatement ps = null;
        ResultSet rs = null;
        try
        {
            ps = borrowPreparedStatement(sql);
            ps.setInt(1, caId);
            ps.setLong(2, serial);
            rs = ps.executeQuery();
            if(rs.next())
            {
                return rs.getInt(1) > 0;
            } else
            {
                return false;
            }
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        }finally
        {
            dataSource.releaseResources(ps, rs);
        }
    }

    void deleteCertInProcess(
            final String fpKey,
            final String fpSubject)
    throws DataAccessException
    {
        final String sql = "DELETE FROM CERT_IN_PROCESS WHERE FP_PK=? AND FP_SUBJECT=?";
        PreparedStatement ps = borrowPreparedStatement(sql);
        ResultSet rs = null;
        try
        {
            ps.setString(1, fpKey);
            ps.setString(2, fpSubject);
            ps.executeUpdate();
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        } finally
        {
            dataSource.releaseResources(ps, rs);
        }
    }

    boolean addCertInProcess(
            final String fpKey,
            final String fpSubject)
    throws DataAccessException
    {
        final String sql = "INSERT INTO CERT_IN_PROCESS (FP_PK, FP_SUBJECT, TIME2) VALUES (?, ?, ?)";
        PreparedStatement ps = borrowPreparedStatement(sql);
        ResultSet rs = null;
        try
        {
            ps.setString(1, fpKey);
            ps.setString(2, fpSubject);
            ps.setLong(3, System.currentTimeMillis() / 1000);
            try
            {
                ps.executeUpdate();
            }catch(SQLException e)
            {
                DataAccessException tEx = dataSource.translate(sql, e);
                if(tEx instanceof DuplicateKeyException || tEx instanceof DataIntegrityViolationException)
                {
                    return false;
                }
            }
            return true;
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
        } finally
        {
            dataSource.releaseResources(ps, rs);
        }
    }

    void deleteCertsInProcessOlderThan(
            final Date time)
    throws DataAccessException
    {
        final String sql = "DELETE FROM CERT_IN_PROCESS WHERE TIME2 < ?";
        PreparedStatement ps = borrowPreparedStatement(sql);
        ResultSet rs = null;
        try
        {
            ps.setLong(1, time.getTime() / 1000);
            ps.executeUpdate();
        }catch(SQLException e)
        {
            throw dataSource.translate(sql, e);
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

}

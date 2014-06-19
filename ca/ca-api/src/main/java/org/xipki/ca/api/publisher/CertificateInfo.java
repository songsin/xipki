/*
 * Copyright (c) 2014 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 *
 */

package org.xipki.ca.api.publisher;

import java.security.cert.CertificateEncodingException;

import org.xipki.ca.common.RequestorInfo;
import org.xipki.ca.common.X509CertificateWithMetaInfo;
import org.xipki.security.common.CertRevocationInfo;
import org.xipki.security.common.ParamChecker;

/**
 * @author Lijun Liao
 */

public class CertificateInfo
{
    private final byte[] subjectPublicKey;
    private final X509CertificateWithMetaInfo cert;
    private final X509CertificateWithMetaInfo issuerCert;
    private final String profileName;

    private RequestorInfo requestor;
    private String user;

    private String warningMessage;

    private CertRevocationInfo revInfo;
    private boolean alreadyIssued;

    public CertificateInfo(X509CertificateWithMetaInfo cert,
            X509CertificateWithMetaInfo issuerCert,
            byte[] subjectPublicKey,
            String profileName)
    throws CertificateEncodingException
    {
        super();
        ParamChecker.assertNotNull("cert", cert);
        ParamChecker.assertNotNull("issuerCert", issuerCert);
        ParamChecker.assertNotEmpty("profileName", profileName);
        ParamChecker.assertNotNull("subjectPublicKey", subjectPublicKey);

        this.cert = cert;
        this.subjectPublicKey = subjectPublicKey;

        this.issuerCert = issuerCert;
        this.profileName = profileName;
    }

    public byte[] getSubjectPublicKey()
    {
        return subjectPublicKey;
    }

    public X509CertificateWithMetaInfo getCert()
    {
        return cert;
    }

    public X509CertificateWithMetaInfo getIssuerCert()
    {
        return issuerCert;
    }

    public String getProfileName()
    {
        return profileName;
    }

    public String getWarningMessage()
    {
        return warningMessage;
    }

    public void setWarningMessage(String warningMessage)
    {
        this.warningMessage = warningMessage;
    }

    public RequestorInfo getRequestor()
    {
        return requestor;
    }

    public void setRequestor(RequestorInfo requestor)
    {
        this.requestor = requestor;
    }

    public String getUser()
    {
        return user;
    }

    public void setUser(String user)
    {
        this.user = user;
    }

    public boolean isRevoked()
    {
        return revInfo != null;
    }

    public CertRevocationInfo getRevocationInfo()
    {
        return revInfo;
    }

    public void setRevocationInfo(CertRevocationInfo revInfo)
    {
        this.revInfo = revInfo;
    }

    public boolean isAlreadyIssued()
    {
        return alreadyIssued;
    }

    public void setAlreadyIssued(boolean alreadyIssued)
    {
        this.alreadyIssued = alreadyIssued;
    }

}

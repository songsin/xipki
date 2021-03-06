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

package org.xipki.ca.client.shell;

import java.io.File;
import java.security.cert.X509Certificate;

import org.apache.karaf.shell.commands.Command;
import org.apache.karaf.shell.commands.Option;
import org.bouncycastle.asn1.pkcs.CertificationRequest;
import org.xipki.ca.client.api.CertOrError;
import org.xipki.ca.client.api.EnrollCertResult;
import org.xipki.common.RequestResponseDebug;
import org.xipki.common.util.IoUtil;
import org.xipki.console.karaf.CmdFailure;

/**
 * @author Lijun Liao
 */

@Command(scope = "xipki-cli", name = "p10-enroll", description="enroll certificate via PKCS#10 request")
public class P10EnrollCertCommand extends ClientCommand
{

    @Option(name = "--p10",
            required = true,
            description = "PKCS#10 request file\n"
                    + "(required)")
    private String p10File;

    @Option(name = "--profile", aliases = "-p",
            required = true,
            description = "certificate profile\n"
                    + "(required)")
    private String profile;

    @Option(name = "--out", aliases = "-o",
            required = true,
            description = "where to save the certificate\n"
                    + "(required)")
    private String outputFile;

    @Option(name = "--user",
            description = "username")
    private String user;

    @Option(name = "--ca",
            description = "CA name\n"
                    + "(required if the profile is supported by more than one CA)")
    private String caName;

    @Override
    protected Object _doExecute()
    throws Exception
    {
        CertificationRequest p10Req = CertificationRequest.getInstance(
                IoUtil.read(p10File));

        EnrollCertResult result;
        RequestResponseDebug debug = getRequestResponseDebug();
        try
        {
            result = caClient.requestCert(p10Req, profile, caName, user, debug);
        }finally
        {
            saveRequestResponse(debug);
        }

        X509Certificate cert = null;
        if(result != null)
        {
            String id = result.getAllIds().iterator().next();
            CertOrError certOrError = result.getCertificateOrError(id);
            cert = (X509Certificate) certOrError.getCertificate();
        }

        if(cert == null)
        {
            throw new CmdFailure("no certificate received from the server");
        }

        File certFile = new File(outputFile);
        saveVerbose("certificate saved to file", certFile, cert.getEncoded());

        return null;
    }

}

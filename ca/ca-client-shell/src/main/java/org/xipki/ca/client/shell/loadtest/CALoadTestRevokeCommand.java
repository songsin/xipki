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

package org.xipki.ca.client.shell.loadtest;

import java.io.FileInputStream;
import java.util.Properties;

import org.apache.karaf.shell.commands.Command;
import org.apache.karaf.shell.commands.Option;
import org.bouncycastle.asn1.x509.Certificate;
import org.xipki.common.AbstractLoadTest;
import org.xipki.common.util.IoUtil;
import org.xipki.console.karaf.IllegalCmdParamException;
import org.xipki.datasource.api.DataSourceFactory;
import org.xipki.datasource.api.DataSourceWrapper;
import org.xipki.security.api.SecurityFactory;

/**
 * @author Lijun Liao
 */

@Command(scope = "xipki-cli", name = "loadtest-revoke", description="CA Client Revoke Load test")
public class CALoadTestRevokeCommand extends CALoadTestCommand
{
    @Option(name = "--issuer",
            required = true,
            description = "issuer certificate file\n"
                    + "(required)")
    private String issuerCertFile;

    @Option(name = "--duration",
            description = "maximal duration in seconds")
    private Integer durationInSecond = 30;

    @Option(name = "--thread",
            description = "number of threads")
    private Integer numThreads = 5;

    @Option(name = "--ca-db",
            required = true,
            description = "CA database configuration file\n"
                    + "(required)")
    private String caDbConfFile;

    @Option(name = "--max-certs",
            description = "maximal number of certificates to be revoked\n"
                    + "0 for unlimited")
    private Integer maxCerts = 0;

    @Option(name = "-n",
            description = "number of certificates to be revoked in one request")
    private Integer n = 1;

    private DataSourceFactory dataSourceFactory;
    private SecurityFactory securityFactory;

    @Override
    protected Object _doExecute()
    throws Exception
    {
        if(numThreads < 1)
        {
            throw new IllegalCmdParamException("invalid number of threads " + numThreads);
        }

        if(durationInSecond < 1)
        {
            throw new IllegalCmdParamException("invalid duration " + durationInSecond);
        }

        StringBuilder startMsg = new StringBuilder();

        startMsg.append("threads:         ").append(numThreads).append("\n");
        startMsg.append("max. duration:   ").append(AbstractLoadTest.formatTime(durationInSecond).trim()).append("\n");
        startMsg.append("issuer:          ").append(issuerCertFile).append("\n");
        startMsg.append("cadb:            ").append(caDbConfFile).append("\n");
        startMsg.append("maxCerts:        ").append(maxCerts).append("\n");
        startMsg.append("#certs/request:  ").append(n).append("\n");
        startMsg.append("unit:            ").append(n).append(" certificate");
        if(n > 1)
        {
            startMsg.append("s");
        }
        startMsg.append("\n");
        out(startMsg.toString());

        Certificate caCert = Certificate.getInstance(IoUtil.read(issuerCertFile));
        Properties props = new Properties();
        props.load(new FileInputStream(IoUtil.expandFilepath(caDbConfFile)));
        props.setProperty("autoCommit", "false");
        props.setProperty("readOnly", "true");
        props.setProperty("maximumPoolSize", "1");
        props.setProperty("minimumIdle", "1");

        DataSourceWrapper caDataSource = dataSourceFactory.createDataSource(
                null, props, securityFactory.getPasswordResolver());
        try
        {
            CALoadTestRevoke loadTest = new CALoadTestRevoke(caClient, caCert, caDataSource, maxCerts, n);

            loadTest.setDuration(durationInSecond);
            loadTest.setThreads(numThreads);
            loadTest.test();
        }finally
        {
            caDataSource.shutdown();
        }

        return null;
    }

    public void setDataSourceFactory(
            final DataSourceFactory dataSourceFactory)
    {
        this.dataSourceFactory = dataSourceFactory;
    }

    public void setSecurityFactory(
            final SecurityFactory securityFactory)
    {
        this.securityFactory = securityFactory;
    }
}

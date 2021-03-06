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

package org.xipki.dbtool.shell;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import jline.console.ConsoleReader;

import org.apache.karaf.shell.commands.Option;
import org.xipki.common.util.IoUtil;
import org.xipki.console.karaf.XipkiOsgiCommandSupport;
import org.xipki.dbtool.LiquibaseDatabaseConf;
import org.xipki.dbtool.LiquibaseMain;
import org.xipki.security.api.PasswordResolver;
import org.xipki.security.api.PasswordResolverException;

/**
 * @author Lijun Liao
 */

public abstract class LiquibaseCommand extends XipkiOsgiCommandSupport
{
    private static final String DFLT_CACONF_FILE = "xipki/ca-config/ca.properties";

    private static final Set<String> yesNo = new HashSet<>();

    private PasswordResolver passwordResolver;

    static
    {
        yesNo.add("yes");
        yesNo.add("no");
    }

    @Option(name = "--quiet", aliases="-q",
            description = "quiet mode")
    private Boolean quiet = Boolean.FALSE;

    @Option(name = "--log-level",
            description = "log level, valid values are debug, info, warning, severe, off")
    private String logLevel = "warning";

    @Option(name = "--ca-conf",
            description = "CA configuration file")
    private String caconfFile = DFLT_CACONF_FILE;

    protected void resetAndInit(
            final LiquibaseDatabaseConf dbConf,
            final String schemaFile)
    throws Exception
    {
        printDatabaseInfo(dbConf, schemaFile);
        if(quiet == false)
        {
            if(confirm("reset and initialize") == false)
            {
                out("cancelled");
                return;
            }
        }

        LiquibaseMain liquibase = new LiquibaseMain(dbConf, schemaFile);
        try
        {
            liquibase.init(logLevel);
            liquibase.releaseLocks();

            if(LiquibaseMain.loglevelIsSevereOrOff(logLevel) == false)
            {
                liquibase.init("severe");
            }
            liquibase.dropAll();

            if(LiquibaseMain.loglevelIsSevereOrOff(logLevel) == false)
            {
                liquibase.init(logLevel);
            }
            liquibase.update();
        }finally
        {
            liquibase.shutdown();
        }

    }

    private static Properties getDbConfPoperties(
            final String dbconfFile)
    throws FileNotFoundException, IOException
    {
        Properties props = new Properties();
        props.load(new FileInputStream(IoUtil.expandFilepath(dbconfFile)));
        return props;
    }

    protected Map<String, LiquibaseDatabaseConf> getDatabaseConfs()
    throws FileNotFoundException, IOException, PasswordResolverException
    {
        Map<String, LiquibaseDatabaseConf> ret = new HashMap<>();
        Properties props = getPropertiesFromFile(caconfFile);
        for(Object objKey : props.keySet())
        {
            String key = (String) objKey;
            if(key.startsWith("datasource."))
            {
                String datasourceFile = props.getProperty(key);
                String datasourceName = key.substring("datasource.".length());
                Properties dbConf = getDbConfPoperties(datasourceFile);
                LiquibaseDatabaseConf dbParams = LiquibaseDatabaseConf.getInstance(dbConf, passwordResolver);
                ret.put(datasourceName, dbParams);
            }
        }

        return ret;
    }

    private static Properties getPropertiesFromFile(
            final String propFile)
    throws FileNotFoundException, IOException
    {
        Properties props = new Properties();
        props.load(new FileInputStream(IoUtil.expandFilepath(propFile)));
        return props;
    }

    private void printDatabaseInfo(
            final LiquibaseDatabaseConf dbParams,
            final String schemaFile)
    {
        StringBuilder msg = new StringBuilder();
        msg.append("\n--------------------------------------------\n");
        msg.append("driver      = ").append(dbParams.getDriver()).append("\n");
        msg.append("user        = ").append(dbParams.getUsername()).append("\n");
        msg.append("url         = ").append(dbParams.getUrl()).append("\n");
        if(dbParams.getSchema() != null)
        {
            msg.append("schema      = ").append(dbParams.getSchema()).append("\n");
        }
        msg.append("schema file = ").append(schemaFile).append("\n");

        System.out.println(msg);
    }

    private boolean confirm(
            final String command)
    throws IOException
    {
        String text = read("\nDo you wish to " + command + " the database", yesNo);
        return "yes".equalsIgnoreCase(text);
    }

    private String read(
            String prompt,
            Set<String> validValues)
    throws IOException
    {
        if(validValues == null)
        {
            validValues = Collections.emptySet();
        }

        if(prompt == null)
        {
            prompt = "Please enter";
        }

        if(isNotEmpty(validValues))
        {
            StringBuilder promptBuilder = new StringBuilder(prompt);
            promptBuilder.append(" [");

            for(String validValue : validValues)
            {
                promptBuilder.append(validValue).append("/");
            }
            promptBuilder.deleteCharAt(promptBuilder.length() - 1);
            promptBuilder.append("] ?");

            prompt = promptBuilder.toString();
        }

        ConsoleReader reader = (ConsoleReader) session.get(".jline.reader");

        out(prompt);
        while(true)
        {
            String answer = reader.readLine();
            if(answer == null)
            {
                throw new IOException("interrupted");
            }

            if(isEmpty(validValues) || validValues.contains(answer))
            {
                return answer;
            }
            else
            {
                StringBuilder retryPromptBuilder = new StringBuilder("Please answer with ");
                for(String validValue : validValues)
                {
                    retryPromptBuilder.append(validValue).append("/");
                }
                retryPromptBuilder.deleteCharAt(retryPromptBuilder.length() - 1);
                out(retryPromptBuilder.toString());
            }
        }
    }

    public void setPasswordResolver(
            final PasswordResolver passwordResolver)
    {
        this.passwordResolver = passwordResolver;
    }
}

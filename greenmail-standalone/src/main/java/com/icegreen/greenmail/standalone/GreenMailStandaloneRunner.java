package com.icegreen.greenmail.standalone;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.configuration.PropertiesBasedGreenMailConfigurationBuilder;
import com.icegreen.greenmail.imap.AuthorizationException;
import com.icegreen.greenmail.imap.ImapHostManager;
import com.icegreen.greenmail.server.BuildInfo;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.user.UserManager;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.PropertiesBasedServerSetupBuilder;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.apache.logging.log4j.core.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Properties;

/**
 * Enables GreenMail to run in standalone mode.
 * <p>
 * Example: java  -Dgreenmail.smtp -Dgreenmail.users=test1:pwd1 -jar greenmail.jar
 *
 * @see PropertiesBasedServerSetupBuilder
 * @see PropertiesBasedGreenMailConfigurationBuilder
 */
public class GreenMailStandaloneRunner {
    private final Logger log = LoggerFactory.getLogger(GreenMailStandaloneRunner.class);
    private GreenMail greenMail;
    private GreenMailApiServerBuilder.ApiServer apiServer;

    /**
     * Start and configure GreenMail using given properties.
     *
     * @param properties the properties such as System.getProperties()
     */
    public void doRun(Properties properties) {
        ServerSetup[] serverSetups = new PropertiesBasedServerSetupBuilder().build(properties);

        if (serverSetups.length == 0) {
            printUsage(System.out);

        } else {
            greenMail = new GreenMail(serverSetups);
            log.info("Starting GreenMail standalone v{} using {}",
                BuildInfo.INSTANCE.getProjectVersion(), Arrays.toString(serverSetups));
            GreenMailConfiguration configuration = new PropertiesBasedGreenMailConfigurationBuilder().build(properties);
            greenMail.withConfiguration(configuration)
                .start();

            GreenMailApiServerBuilder apiBuilder = new GreenMailApiServerBuilder().configure(properties);
            if (apiBuilder.isEnabled()) {
                apiServer = apiBuilder.withGreenMail(greenMail, serverSetups, configuration).build();
                log.info("Starting GreenMail API server at {}", apiServer.getUri());
                apiServer.start();
            }

            postStart( properties );
        }
    }

    /**
     * Run post startup commands create folders, add .eml files or director of .eml files
     *
     * @param properties configuration
     */
    protected void postStart( Properties properties ) {
        // greenmail.foldersCreate = "userId:folder1,folder2,..."
        if( properties.containsKey( "greenmail.foldersCreate" ) ) {
            final String foldersProp = properties.getProperty( "greenmail.foldersCreate" );
            final String foldersUser = foldersProp.substring( 0, foldersProp.indexOf( ":" ) );
            final String[] folderNames = foldersProp.substring( foldersProp.indexOf( ":" ) + 1 ).split( "," );

            final UserManager userManager = greenMail.getManagers().getUserManager();
            GreenMailUser gmu = userManager.getUser( foldersUser );
            try {
                final ImapHostManager imapHostManager = greenMail.getManagers().getImapHostManager();
                for( String folderName : folderNames )
                    imapHostManager.createMailbox( gmu, folderName );
            } catch( AuthorizationException | FolderException ex ) {
                log.error( "Error creating folder {}, message: {}", folderNames, ex.getMessage() );
            }
        }

         // greenmail.emlFilesDirLoad = "userId:/path/to/emls/dir"
        if( properties.containsKey( "greenmail.emlFilesDirLoad" ) ) {
            final String emlDirProp = properties.getProperty( "greenmail.emlFilesDirLoad" );
            final String emlUser = emlDirProp.substring( 0, emlDirProp.indexOf( ":" ) );
            final String emlDir = emlDirProp.substring( emlDirProp.indexOf( ":" ) + 1 );

            try {
                File emlLoadDir = new File( emlDir );
                File[] emlFiles = emlLoadDir.listFiles( pathname -> !pathname.toString().contains( "README" ) );

                if( emlFiles == null || emlFiles.length == 0 ) {
                    log.warn( "No eml files to load: {}", emlDir );
                    return;
                }

                for( File emlFile : emlFiles ) {
                    emlFileInboxAdd( emlUser, emlFile );
                }
            } catch( Exception ex ) {
                log.error( "Exception loading {} eml file", properties.getProperty( "greenmail.imap.loadEmlFile" ) );
            }
        }

        // greenmail.emlFileLoad="userId:/path/to/eml/<some file name>.eml"
        if( properties.containsKey( "greenmail.emlFileLoad" ) ) {
            final String emlFileProp = properties.getProperty( "greenmail.imap.loadEmlFile" );
            final String emlUser = emlFileProp.substring( 0, emlFileProp.indexOf( ":" ) );
            final String emlFile = emlFileProp.substring( emlFileProp.indexOf( ":" ) + 1 );

            try {
                emlFileInboxAdd( emlUser, new File( emlFile ) );
            } catch( Exception ex ) {
                log.error( "Exception {} loading {} eml file", ex.getClass().getSimpleName(), properties.getProperty( "greenmail.imap.loadEmlFile" ) );
            }
        }
    }

    /**
     * add a .eml file to a users INBOX
     *
     * @param emlUser greenmail user
     * @param emlFile .eml file in question
     *
     * @throws Exception if there are any issues
     */
    public void emlFileInboxAdd( final String emlUser, final File emlFile ) throws Exception {
        final UserManager userManager = greenMail.getManagers().getUserManager();
        GreenMailUser gmu = userManager.getUser( emlUser );

        final Session session = greenMail.getSmtp().createSession();
        final ImapHostManager imapHostManager = greenMail.getManagers().getImapHostManager();
        try( InputStream source = Files.newInputStream( emlFile.toPath() ) ) {
            final MimeMessage loadedMsg = new MimeMessage( session, source );
            imapHostManager.getFolder( gmu, "INBOX" ).store( loadedMsg );
        }
    }

    protected static void configureLogging(Properties properties) {
        // Init logging: Try standard log4j2 configuration mechanism before falling back to
        // provided logging configuration
        final String log4jConfigFilePropertyName = "log4j2.configurationFile";
        String log4jConfig = System.getProperty(log4jConfigFilePropertyName);
        if (null == log4jConfig) {
            if (properties.containsKey(PropertiesBasedServerSetupBuilder.GREENMAIL_VERBOSE)) {
                System.setProperty(log4jConfigFilePropertyName, "log4j2-verbose.xml");
            } else {
                System.setProperty(log4jConfigFilePropertyName, "log4j2.xml");
            }
        }
        LoggerContext.getContext();

        // Bridge Java Util Logging to SLF4j for Jersey
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    void stop() {
        if (null != apiServer) {
            apiServer.stop();
        }
        greenMail.stop();
    }

    private void printUsage(PrintStream out) {
        // Don't use logger
        out.println("Usage: java OPTIONS -jar greenmail.jar");
        out.println("\nOPTIONS:");
        String[][] options = {
            {"-Dgreenmail.setup.<protocol|all>", "Specifies mail server to start using default port and bind " +
                "address 127.0.0.1"},
            {"Note: Protocol can be one of smtp,smtps,imap,imaps,pop3,pop3s or api"},
            {"-Dgreenmail.setup.test.<protocol|all>", "Specifies mail server to start using default port plus " +
                "offset 3000 and bind address 127.0.0.1"},
            {"-Dgreenmail.<protocol|all>.hostname=...",
                "Specifies bind address. Requires additional port parameter."},
            {"-Dgreenmail.<protocol>.port=...", "Specifies port. Requires additional hostname parameter."},
            {"-Dgreenmail.users=<logon:pwd@domain>[,...]", "Specifies mail users, eg foo:pwd@bar.com,foo2:pwd@bar2.com."},
            {"Note: domain must be DNS resolvable!"},
            {"-Dgreenmail.users.login=(local_part|email)",
                "Configures if local_part (default) or full email should be used for login when" +
                    " configuring users via -Dgreenmail.users=foo@bar.com,...."},
            {"Note: Only has effect if configured user is of type email (i.e. contains '@')"},
            {"-Dgreenmail.auth.disabled ", "Disables authentication check so that any password works."},
            {"Also automatically provisions previously non-existent users."},
            {"-Dgreenmail.verbose ", "Enables verbose mode, including JavaMail debug output"},
            {"-Dgreenmail.startup.timeout=<TIMEOUT_IN_MILLISECONDS>",
                "Overrides the default server startup timeout of 1000ms."},
        };
        for (String[] opt : options) {
            if (opt.length == 1) {
                out.printf("%1$44s %2$s%n", " ", opt[0]);
            } else {
                out.printf("%1$-42s : %2$s%n", (Object[]) opt); // NOSONAR
            }
        }
        out.println();
        out.println("Example: ");
        out.println(" java -Dgreenmail.setup.test.all -Dgreenmail.users=foo:pwd@bar.com -jar greenmail.jar");
        out.println("       Starts SMTP,SMTPS,IMAP,IMAPS,POP3,POP3S" +
            "with default ports plus offset 3000 on 127.0.0.1 and user foo@bar.com.");
        out.println("       Note: bar.com domain for user must be DNS resolvable!");
        out.println(" java -Dgreenmail.setup.test.smtp -Dgreenmail.setup.test.imap -Dgreenmail.auth.disabled -jar greenmail.jar");
        out.println("       Starts SMTP on 127.0.01:3025 and IMAP on 127.0.0.1:3143, disabling user authentication");
        out.println(" java -Dgreenmail.smtp.hostname=0.0.0.0 -Dgreenmail.smtp.port=3025 " +
            "-Dgreenmail.imap.hostname=0.0.0.0 -Dgreenmail.imap.port=3143 -jar greenmail.jar");
        out.println("       Starts SMTP on 0.0.0.0:3025 and IMAP on 0.0.0.0:3143");
    }

    GreenMail getGreenMail() {
        return greenMail;
    }

    public static void main(String[] args) {
        final Properties properties = System.getProperties();
        configureLogging(properties);
        new GreenMailStandaloneRunner().doRun(properties);
    }
}

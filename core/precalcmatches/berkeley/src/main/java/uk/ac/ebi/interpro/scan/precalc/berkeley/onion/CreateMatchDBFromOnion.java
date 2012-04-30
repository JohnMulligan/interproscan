package uk.ac.ebi.interpro.scan.precalc.berkeley.onion;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;
import com.sleepycat.persist.StoreConfig;
import uk.ac.ebi.interpro.scan.model.PersistenceConversion;
import uk.ac.ebi.interpro.scan.precalc.berkeley.conversion.toi5.SignatureLibraryLookup;
import uk.ac.ebi.interpro.scan.precalc.berkeley.model.BerkeleyLocation;
import uk.ac.ebi.interpro.scan.precalc.berkeley.model.BerkeleyMatch;

import java.io.File;
import java.sql.*;

/**
 * Creates a Berkeley database of matches from Onion.
 * <p/>
 * NOTE: THIS IS NOT FOR PRODUCTION USE - This code is intended only to
 * build a BerkeleyDB suitable for scale-testing pre-calculated
 * match lookup.
 * <p/>
 * Note: This query at the time of writing returns
 * 167,594,822 rows of match data!?
 *
 * @author Phil Jones
 * @version $Id$
 * @since 1.0-SNAPSHOT
 */
public class CreateMatchDBFromOnion {

    private static final int COL_IDX_MD5 = 1;
    private static final int COL_IDX_SIG_LIB_NAME = 2;
    private static final int COL_IDX_SIG_LIB_RELEASE = 3;
    private static final int COL_IDX_SIG_ACCESSION = 4;
    private static final int COL_IDX_SEQ_SCORE = 5;
    private static final int COL_IDX_EVALUE = 6;
    private static final int COL_IDX_SEQ_START = 7;
    private static final int COL_IDX_SEQ_END = 8;
    private static final int COL_IDX_HMM_START = 9;
    private static final int COL_IDX_HMM_END = 10;
    private static final int COL_IDX_HMM_BOUNDS = 11;

    private static final String CREATE_TEMP_TABLE =
            "create global temporary table  berkley_tmp_tab " +
                    "on commit preserve rows " +
                    "as " +
                    "select p.md5 as protein_md5, " +
                    "          analt.name as signature_library_name, " +
                    "          m.relno_major || '.' || m.relno_minor as signature_library_release, " +
                    "          m.method_ac as signature_accession, " +
                    "          m.seqscore as sequence_score, " +
                    "          m.evalue, " +
                    "          m.seq_start, " +
                    "          m.seq_end, " +
                    "          m.hmm_start, " +
                    "          m.hmm_end, " +
                    "          m.hmm_bounds " +
                    "     from onion.cv_analysis_type analt, " +
                    "          onion.iprscan m, onion.uniparc_protein p " +
                    "    where m.status = 'T' " +
                    "          and analt.name in ('PANTHER', 'SMART', 'PRINTS', 'PROSITE_PF', 'PROSITE_PT', 'PIRSF', 'PRODOM', 'SSF', 'HAMAP', 'PFAM_HMMER3', 'COILS', 'GENE3D_HMMER3', 'TIGRFAM_HMMER3', 'SIGNALP_EUK', 'SIGNALP_GRAM+', 'SIGNALP_GRAM-') " +
                    "          and m.UPI <= 'MAX_UPI' " +
                    "          and analt.analysis_type_id = m.analysis_type_id " +
                    "          and m.upi = p.upi ";

    private static final String ADD_SIGNAL_P_DATA =
            "insert into berkley_tmp_tab " +
                    "select p.md5 as protein_md5, " +
                    "     analt.name as signature_library_name, " +
                    "     '4.0' as signature_library_release, " +
                    "     'SignalPeptide' as signature_accession, " +
                    "     null as sequence_score, " +
                    "     null as evalue, " +
                    "     1 as seq_start, " +
                    "     m.meand_pos as seq_end, " +
                    "     null as hmm_start, " +
                    "     null as hmm_end, " +
                    "     null as hmm_bounds " +
                    "   from onion.cv_analysis_type analt " +
                    "     inner join " +
                    "     onion.signalp_analysis m on analt.analysis_type_id = m.analysis_type_id " +
                    "     inner join onion.uniparc_protein p on m.upi = p.upi " +
                    "   where m.meand_pred = '1' " +
                    "         and m.upi <= 'MAX_UPI' " +
                    "   order by p.md5";

    private static final String QUERY_TEMPORARY_TABLE =
            "select  PROTEIN_MD5, SIGNATURE_LIBRARY_NAME, SIGNATURE_LIBRARY_RELEASE, SIGNATURE_ACCESSION, " +
                    "       SEQUENCE_SCORE, EVALUE, SEQ_START, SEQ_END, HMM_START, HMM_END, HMM_BOUNDS " +
                    "       from  berkley_tmp_tab " +
                    "       order by  PROTEIN_MD5, SIGNATURE_LIBRARY_NAME, SIGNATURE_LIBRARY_RELEASE, SIGNATURE_ACCESSION, " +
                    "       SEQUENCE_SCORE";

    private static final String TRUNCATE_TEMPORARY_TABLE =
            "truncate table berkley_tmp_tab";

    private static final String DROP_TEMPORARY_TABLE =
            "drop  table berkley_tmp_tab";


    public static void main(String[] args) {
        if (args.length < 4) {
            throw new IllegalArgumentException("Please provide the following arguments:\n\npath to berkeleyDB directory\nOnion DB URL (jdbc:oracle:thin:@host:port:SID)\nOnion DB username\nOnion DB password\nMaximum UPI");
        }
        String directoryPath = args[0];
        String onionDBUrl = args[1];
        String onionUsername = args[2];
        String onionPassword = args[3];
        String maxUPI = args[4];

        CreateMatchDBFromOnion instance = new CreateMatchDBFromOnion();

        instance.buildDatabase(directoryPath,
                onionDBUrl,
                onionUsername,
                onionPassword,
                maxUPI
        );
    }

    void buildDatabase(String directoryPath, String onionDBUrl, String onionUsername, String onionPassword, String maxUPI) {
        long startMillis = System.currentTimeMillis();
        Environment myEnv = null;
        EntityStore store = null;
        Connection onionConn = null;

        try {
            // Connect to the Onion database.
            Class.forName("oracle.jdbc.OracleDriver");
            onionConn = DriverManager.getConnection(onionDBUrl, onionUsername, onionPassword);

            // First, create the populate the temporary table before create the BerkeleyDB, to prevent timeouts.
            Statement statement = null;
            try {
                statement = onionConn.createStatement();
                statement.execute(CREATE_TEMP_TABLE.replace("MAX_UPI", maxUPI));
            } finally {
                if (statement != null) {
                    statement.close();
                }
            }

            long now = System.currentTimeMillis();
            System.out.println((now - startMillis) + " milliseconds to create the temporary table.");
            startMillis = now;

            // Then add the additional SignalP data
            statement = null;
            try {
                statement = onionConn.createStatement();
                statement.execute(ADD_SIGNAL_P_DATA.replace("MAX_UPI", maxUPI));
            } finally {
                if (statement != null) {
                    statement.close();
                }
            }
            now = System.currentTimeMillis();
            System.out.println((now - startMillis) + " milliseconds to add the SignalP data.");
            startMillis = now;

            PrimaryIndex<Long, BerkeleyMatch> primIDX = null;

            PreparedStatement ps = null;
            ResultSet rs = null;
            try {
                ps = onionConn.prepareStatement(QUERY_TEMPORARY_TABLE);
                rs = ps.executeQuery();
                BerkeleyMatch match = null;

                int locationCount = 0, matchCount = 0;

                while (rs.next()) {
                    // Open the BerkeleyDB at the VERY LAST MOMENT - prevent timeouts.
                    if (primIDX == null) {
                        // Now create the berkeley database directory is present and writable.
                        File berkeleyDBDirectory = new File(directoryPath);
                        if (berkeleyDBDirectory.exists()) {
                            if (!berkeleyDBDirectory.isDirectory()) {
                                throw new IllegalStateException("The path " + directoryPath + " already exists and is not a directory, as required for a Berkeley Database.");
                            }
                            File[] directoryContents = berkeleyDBDirectory.listFiles();
                            if (directoryContents != null && directoryContents.length > 0) {
                                throw new IllegalStateException("The directory " + directoryPath + " already has some contents.  The " + CreateMatchDBFromOnion.class.getSimpleName() + " class is expecting an empty directory path name as argument.");
                            }
                            if (!berkeleyDBDirectory.canWrite()) {
                                throw new IllegalStateException("The directory " + directoryPath + " is not writable.");
                            }
                        } else if (!(berkeleyDBDirectory.mkdirs())) {
                            throw new IllegalStateException("Unable to create Berkeley database directory " + directoryPath);
                        }

                        // Open up the Berkeley Database
                        EnvironmentConfig myEnvConfig = new EnvironmentConfig();
                        StoreConfig storeConfig = new StoreConfig();

                        myEnvConfig.setAllowCreate(true);
                        storeConfig.setAllowCreate(true);
                        storeConfig.setTransactional(false);
                        // Open the environment and entity store
                        myEnv = new Environment(berkeleyDBDirectory, myEnvConfig);
                        store = new EntityStore(myEnv, "EntityStore", storeConfig);

                        primIDX = store.getPrimaryIndex(Long.class, BerkeleyMatch.class);

                    }
                    // Only process if the SignatureLibraryName is recognised.
                    final String signatureLibraryName = rs.getString(COL_IDX_SIG_LIB_NAME);
                    if (rs.wasNull() || signatureLibraryName == null) continue;
                    if (SignatureLibraryLookup.lookupSignatureLibrary(signatureLibraryName) == null) continue;

                    // Now collect rest of the data and test for mandatory fields.
                    final int sequenceStart = rs.getInt(COL_IDX_SEQ_START);
                    if (rs.wasNull()) continue;

                    final int sequenceEnd = rs.getInt(COL_IDX_SEQ_END);
                    if (rs.wasNull()) continue;

                    final String proteinMD5 = rs.getString(COL_IDX_MD5);
                    if (proteinMD5 == null || proteinMD5.length() == 0) continue;

                    final String sigLibRelease = rs.getString(COL_IDX_SIG_LIB_RELEASE);
                    if (sigLibRelease == null || sigLibRelease.length() == 0) continue;

                    final String signatureAccession = rs.getString(COL_IDX_SIG_ACCESSION);
                    if (signatureAccession == null || signatureAccession.length() == 0) continue;

                    Integer hmmStart = rs.getInt(COL_IDX_HMM_START);
                    if (rs.wasNull()) hmmStart = null;

                    Integer hmmEnd = rs.getInt(COL_IDX_HMM_END);
                    if (rs.wasNull()) hmmEnd = null;

                    String hmmBounds = rs.getString(COL_IDX_HMM_BOUNDS);

                    Double sequenceScore = rs.getDouble(COL_IDX_SEQ_SCORE);
                    if (rs.wasNull()) sequenceScore = null;

                    Double eValue = rs.getDouble(COL_IDX_EVALUE);
                    if (rs.wasNull()) {
                        eValue = null;
                    } else {
                        // Stored in Onion as Log Base 10.  Convert back...
                        if (eValue == Double.NaN || eValue == Double.POSITIVE_INFINITY || eValue == Double.NEGATIVE_INFINITY) {
                            eValue = 0d;
                        } else {
                            eValue = PersistenceConversion.get(eValue);
                        }
                    }


                    /// arrgggh!  The IPRSCAN table stores PRINTS Graphscan values in the hmmBounds column...

                    final BerkeleyLocation location = new BerkeleyLocation();
                    location.setStart(sequenceStart);
                    location.setEnd(sequenceEnd);
                    location.setHmmStart(hmmStart);
                    location.setHmmEnd(hmmEnd);
                    location.setHmmBounds(hmmBounds);
                    location.seteValue(eValue);
                    locationCount++;

                    if (match != null) {
                        if (
                                proteinMD5.equals(match.getProteinMD5()) &&
                                        signatureLibraryName.equals(match.getSignatureLibraryName()) &&
                                        sigLibRelease.equals(match.getSignatureLibraryRelease()) &&
                                        signatureAccession.equals(match.getSignatureAccession()) &&
                                        (match.getSequenceScore() == null && sequenceScore == null || (sequenceScore != null && sequenceScore.equals(match.getSequenceScore())))) {
                            // Same Match as previous, so just add a new BerkeleyLocation
                            match.addLocation(location);
                        } else {
                            // Store last match
                            primIDX.put(match);
                            matchCount++;
                            if (matchCount % 100000 == 0) {
                                System.out.println("Stored " + matchCount + " matches, with a total of " + locationCount + " locations.");
                            }

                            // Create new match and add location to it
                            match = new BerkeleyMatch();
                            match.setProteinMD5(proteinMD5);
                            match.setSignatureLibraryName(signatureLibraryName);
                            match.setSignatureLibraryRelease(sigLibRelease);
                            match.setSignatureAccession(signatureAccession);
                            match.setSequenceScore(sequenceScore);
                            match.addLocation(location);
                        }
                    } else {
                        // Create new match and add location to it
                        match = new BerkeleyMatch();
                        match.setProteinMD5(proteinMD5);
                        match.setSignatureLibraryName(signatureLibraryName);
                        match.setSignatureLibraryRelease(sigLibRelease);
                        match.setSignatureAccession(signatureAccession);
                        match.setSequenceScore(sequenceScore);
                        match.addLocation(location);
                    }
                }
                // Don't forget the last match!
                if (match != null) {
                    primIDX.put(match);
                }
            } finally {
                if (rs != null) rs.close();
                if (ps != null) ps.close();
            }
            now = System.currentTimeMillis();
            System.out.println((now - startMillis) + " milliseconds to query the temporary table and create the BerkeleyDB.");
            startMillis = now;


            // Truncate the temporary table
            // Then add the additional SignalP data
            statement = null;
            try {
                statement = onionConn.createStatement();
                statement.execute(TRUNCATE_TEMPORARY_TABLE);
            } finally {
                if (statement != null) {
                    statement.close();
                }
            }

            now = System.currentTimeMillis();
            System.out.println((now - startMillis) + " milliseconds to truncate the temporary table.");
            startMillis = now;

            // And drop the table
            // Then add the additional SignalP data
            statement = null;
            try {
                statement = onionConn.createStatement();
                statement.execute(DROP_TEMPORARY_TABLE);
            } finally {
                if (statement != null) {
                    statement.close();
                }
            }

            now = System.currentTimeMillis();
            System.out.println((now - startMillis) + " milliseconds to drop the temporary table.");

            System.out.println("Finished building BerkeleyDB.");


        } catch (DatabaseException dbe) {
            throw new IllegalStateException("Error opening the BerkeleyDB environment", dbe);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unable to load the oracle.jdbc.OracleDriver class", e);
        } catch (SQLException e) {
            throw new IllegalStateException("SQLException thrown by Onion", e);
        } finally {
            if (store != null) {
                try {
                    store.close();
                } catch (DatabaseException dbe) {
                    System.out.println("Unable to close the BerkeleyDB connection.");
                }
            }

            if (myEnv != null) {
                try {
                    // Finally, close environment.
                    myEnv.close();
                } catch (DatabaseException dbe) {
                    System.out.println("Unable to close the BerkeleyDB environment.");
                }
            }

            if (onionConn != null) {
                try {
                    onionConn.close();
                } catch (SQLException e) {
                    System.out.println("Unable to close the Onion database connection.");
                }
            }
        }
    }
}

package uk.ac.ebi.interpro.scan.management.model.implementations.panther;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.beans.factory.annotation.Required;
import uk.ac.ebi.interpro.scan.io.match.panther.PantherMatchParser;
import uk.ac.ebi.interpro.scan.management.model.Step;
import uk.ac.ebi.interpro.scan.management.model.StepInstance;
import uk.ac.ebi.interpro.scan.management.model.implementations.ParseStep;
import uk.ac.ebi.interpro.scan.model.SignatureLibrary;
import uk.ac.ebi.interpro.scan.model.raw.PantherRawMatch;
import uk.ac.ebi.interpro.scan.model.raw.RawMatch;
import uk.ac.ebi.interpro.scan.model.raw.RawProtein;
import uk.ac.ebi.interpro.scan.persistence.raw.RawMatchDAO;
import uk.ac.ebi.interpro.scan.util.Utilities;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Parses PANTHER output.
 *
 * @author Antony Quinn
 * @author Gift Nuka
 *
 * @version $Id$
 */

public final class PantherParseStep extends ParseStep<PantherRawMatch> {
}



//public final class PantherParseStep extends Step {
//
//    private static final Logger LOGGER = LogManager.getLogger(PantherParseStep.class.getName());
//    private String outputFileNameTemplate;
//    private RawMatchDAO<PantherRawMatch> rawMatchDAO;
//    private PantherMatchParser parser;
//    private String signatureLibraryRelease;
//
//    @Required
//    public void setOutputFileNameTemplate(String PantherOutputFileNameTemplate) {
//        this.outputFileNameTemplate = PantherOutputFileNameTemplate;
//    }
//
//    public String getOutputFileNameTemplate() {
//        return outputFileNameTemplate;
//    }
//
//    @Required
//    public void setRawMatchDAO(RawMatchDAO<PantherRawMatch> rawMatchDAO) {
//        this.rawMatchDAO = rawMatchDAO;
//    }
//
//    @Required
//    public void setSignatureLibraryRelease(String signatureLibraryRelease) {
//        this.signatureLibraryRelease = signatureLibraryRelease;
//    }
//
//    public String getSignatureLibraryRelease() {
//        return signatureLibraryRelease;
//    }
//
//    public void setParser(PantherMatchParser parser) {
//        this.parser = parser;
//    }
//
//    /**
//     * This method is called to execute the action that the StepInstance must perform.
//     * <p/>
//     * If an error occurs that cannot be immediately recovered from, the implementation
//     * of this method MUST throw a suitable Exception, as the call
//     * to execute is performed within a transaction with the reply to the JMSBroker.
//     *
//     * @param stepInstance           containing the parameters for executing.
//     * @param temporaryFileDirectory
//     * @throws Exception could be anything thrown by the execute method.
//     */
//    @Override
//    public void execute(StepInstance stepInstance, String temporaryFileDirectory) {
//        LOGGER.info("Starting step with Id " + this.getId());
//        InputStream stream = null;
//        try {
//            final String outputFilePath = stepInstance.buildFullyQualifiedFilePath(temporaryFileDirectory, outputFileNameTemplate);
//            stream = new FileInputStream(outputFilePath);
//            final PantherMatchParser parser = this.parser;
//            Set<RawProtein<PantherRawMatch>> parsedResults = parser.parse(stream);
//            RawMatch represantiveRawMatch = null;
//            int matchCount = 0;
//            for (final RawProtein<PantherRawMatch> rawProtein : parsedResults) {
//                matchCount += rawProtein.getMatches().size();
//                if (represantiveRawMatch == null) {
//                    if (rawProtein.getMatches().size() > 0) {
//                        represantiveRawMatch = rawProtein.getMatches().iterator().next();
//                    }
//                }
//            }
//            if (LOGGER.isDebugEnabled()) {
//                LOGGER.debug("PANTHER: Retrieved " + parsedResults.size() + " proteins.");
//                LOGGER.debug("PANTHER: A total of " + matchCount + " raw matches.");
//            }
//
//            // Persist parsed raw matches
//            LOGGER.info("Persisting parsed raw matches...");
//            rawMatchDAO.insertProteinMatches(parsedResults);
//            Long now = System.currentTimeMillis();
//            if (matchCount > 0){
//                int matchesFound = 0;
//                int waitTimeFactor = Utilities.getWaitTimeFactor(matchCount).intValue();
//                if (represantiveRawMatch != null) {
//                    Utilities.verboseLog(1100, "represantiveRawMatch :" + represantiveRawMatch.toString());
//                    String signatureLibraryRelease = represantiveRawMatch.getSignatureLibraryRelease();
//                    int retryCount = 0;
//                    Long allowedWaitTime = Long.valueOf(waitTimeFactor) * waitTimeFactor * 100 * 1000;
//                    while (matchesFound < matchCount) {
//                        retryCount ++;
//                        Utilities.sleep(waitTimeFactor * 1000);
//                        matchesFound = rawMatchDAO.getActualRawMatchesForProteinIdsInRange(stepInstance.getBottomProtein(),
//                                stepInstance.getTopProtein(), signatureLibraryRelease).size();
//                        if (matchesFound < matchCount) {
//                            LOGGER.warn("Raw matches may not yet committed - sleep for" +  waitTimeFactor + " seconds , count: " + matchCount);
//                        }
//                        Long timeTaken = System.currentTimeMillis() - now;
//                        //we try three times then break
//                        if(Utilities.getSequenceCount() < 100 || timeTaken > allowedWaitTime || retryCount > 3){
//                            //just break as something else might be happening
//                            String matchPersistWarning = "Possible database problem: failed to " + retryCount + "x verify " + matchCount + " matches in database for "
//                                    + represantiveRawMatch.getSignatureLibrary().getName()
//                                    + " after " + timeTaken + " ms "
//                                    + " - matches found : " + matchesFound;
//                            LOGGER.warn(matchPersistWarning);
//                            Utilities.verboseLog(matchPersistWarning);
//                            break;
//                        }
//
//                    }
//                }else{
//                    String matchPersistWarning = "Check if Raw matches committed " + matchCount + " repm: " + represantiveRawMatch;
//                    LOGGER.warn(matchPersistWarning);
//                    Utilities.verboseLog(matchPersistWarning);
//                }
//                Long timeTaken = System.currentTimeMillis() - now;
//                Utilities.verboseLog(1100, "ParseStep: count: " + matchCount + " represantiveRawMatch : " + represantiveRawMatch.toString()
//                        + " time taken: " + timeTaken);
//            }
//        } catch (IOException e) {
//            throw new IllegalStateException("IOException thrown when attempting to parse Panther file " + outputFileNameTemplate, e);
//        } finally {
//            if (stream != null) {
//                try {
//                    stream.close();
//                } catch (IOException e) {
//                    LOGGER.error("Unable to close connection to the Panther output file located at " + outputFileNameTemplate, e);
//                }
//            }
//        }
//        LOGGER.info("Step with Id " + this.getId() + " finished.");
//    }
//}

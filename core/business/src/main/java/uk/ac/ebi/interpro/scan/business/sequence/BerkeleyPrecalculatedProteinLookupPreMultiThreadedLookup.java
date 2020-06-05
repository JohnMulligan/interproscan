package uk.ac.ebi.interpro.scan.business.sequence;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import org.springframework.oxm.UnmarshallingFailureException;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.util.Assert;
import uk.ac.ebi.interpro.scan.model.Protein;
import uk.ac.ebi.interpro.scan.model.SignatureLibrary;
import uk.ac.ebi.interpro.scan.model.SignatureLibraryRelease;
import uk.ac.ebi.interpro.scan.precalc.berkeley.conversion.toi5.LookupStoreToI5ModelDAO;
import uk.ac.ebi.interpro.scan.precalc.berkeley.conversion.toi5.SignatureLibraryLookup;
import uk.ac.ebi.interpro.scan.precalc.berkeley.model.SimpleLookupMatch;
import uk.ac.ebi.interpro.scan.precalc.berkeley.model.KVSequenceEntry;
import uk.ac.ebi.interpro.scan.precalc.berkeley.model.KVSequenceEntryXML;
import uk.ac.ebi.interpro.scan.precalc.client.MatchHttpClient;
import uk.ac.ebi.interpro.scan.util.Utilities;

import javax.xml.bind.JAXBException;

import java.io.IOException;
import java.net.*;
import java.util.*;

/**
 * Looks up precalculated matches from the Berkeley WebService.
 *
 * @author Phil Jones
 * @version $Id$
 * @since 1.0-SNAPSHOT
 */
public class BerkeleyPrecalculatedProteinLookupPreMultiThreadedLookup implements PrecalculatedProteinLookup {

    Logger LOGGER = LogManager.getLogger(BerkeleyPrecalculatedProteinLookupPreMultiThreadedLookup.class.getName());

    /**
     * This client is used to check for existing matches
     * from a web service.  This web service will be provided directly
     * from the EBI, but can also be easily installed locally.
     * (Runs from a BerkeleyDB on Jetty, so can be run from a single
     * command).
     * <p>
     * Essentially, the client will be used if it is available.
     */
    private MatchHttpClient preCalcMatchClient;

    private LookupStoreToI5ModelDAO lookupStoreToI5ModelDAO;

    private String interproscanVersion;

    private Long timeLookupError = null;

    private Long timeLookupSynchronisationError = null;

    private int totalLookedup = 0;

    public BerkeleyPrecalculatedProteinLookupPreMultiThreadedLookup() {


    }

    @Required
    public void setInterproscanVersion(String interproscanVersion) {
        Assert.notNull(interproscanVersion, "Interproscan version cannot be null");
        this.interproscanVersion = interproscanVersion;
    }

    @Required
    public void setLookupStoreToI5ModelDAO(LookupStoreToI5ModelDAO lookupStoreToI5ModelDAO) {
        this.lookupStoreToI5ModelDAO = lookupStoreToI5ModelDAO;
    }

    /**
     * This client is used to check for existing matches
     * from a web service.  This web service will be provided directly
     * from the EBI, but can also be easily installed locally.
     * (Runs from a BerkeleyDB on Jetty, so can be run from a single
     * command).
     * <p>
     * Essentially, the client will be used if it is available.
     */
    @Required
    public void setPreCalcMatchClient(MatchHttpClient preCalcMatchClient) {
        this.preCalcMatchClient = preCalcMatchClient;
    }

    /**
     * Note - this method returns null if there are no precalculated results.
     *
     * @param protein
     * @return
     */
    @Override
    public Protein getPrecalculated(Protein protein, Map<String, SignatureLibraryRelease> analysisJobMap) {
        // Check if the precalc service is configure and available.
        if (!preCalcMatchClient.isConfigured()) {
            return null;
        }

        // Check if the MD5 needs to be reanalyzed
        String lookupMessageStatus = "Checking lookup client and server are in sync";
        try {
            // Only proceed if the lookup client and server are in sync
            if (!isSynchronised()) {
                return null;
            }
            final String upperMD5 = protein.getMd5().toUpperCase();

            lookupMessageStatus = "Check MD5s of proteins analysed previously";

            if (!preCalcMatchClient.getMD5sOfProteinsAlreadyAnalysed(upperMD5).contains(upperMD5)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Protein with MD5 " + upperMD5 + " has not been analysed previously, so the analysis needs to be run.");
                }
                return null;  // Needs to be analysed.
            }
            // Now retrieve the Matches and add to the protein.
            Long startTime = null;
            if (LOGGER.isDebugEnabled()) {
                startTime = System.nanoTime();
            }
            lookupMessageStatus = "Get matches of proteins analysed previously";
//            final KVSequenceEntryXML kvSequenceEntryXML = preCalcMatchClient.getMatches(upperMD5);
            final KVSequenceEntryXML kvSequenceEntryXML = getMatchesFromLookup(upperMD5);
            if (kvSequenceEntryXML == null){
                Utilities.verboseLog(110, "For this batch, calculate the matches locally - md5 =  " + upperMD5);
                return null;
            }

            long timetaken = System.nanoTime() - startTime;
            long lookupTimeMillis = 0;
            if (timetaken > 0) {
                lookupTimeMillis = timetaken / 1000000;
            }

            Utilities.verboseLog(110, "Time to lookup " + kvSequenceEntryXML.getMatches().size() + " matches for one protein: " + lookupTimeMillis + " millis");

            if (LOGGER.isDebugEnabled()) {

                LOGGER.debug("Time to lookup " + kvSequenceEntryXML.getMatches().size() + " matches for one protein: " + timetaken + "ns");
            }
            if (kvSequenceEntryXML != null) {
                boolean includeCDDorSFLD = includeCDDorSFLD(analysisJobMap);
                KVSequenceEntryXML kvSitesSequenceEntryXML = null;
                if(includeCDDorSFLD){
                    Utilities.verboseLog(1100, "lookup Sites ... ");
                    kvSitesSequenceEntryXML = getSitesFromLookup(upperMD5);
                    Utilities.verboseLog(1100, "lookup Sites XML:" + kvSitesSequenceEntryXML.toString());
                }
                lookupStoreToI5ModelDAO.populateProteinMatches(protein, kvSequenceEntryXML.getMatches(), kvSitesSequenceEntryXML.getMatches(), analysisJobMap, includeCDDorSFLD);
            }

            return protein;
        } catch (Exception e) {
            hostAvailabilityCheck(preCalcMatchClient.getUrl());
            displayLookupError(e, lookupMessageStatus);
            return null;
        }

    }

    @Override
    public Set<Protein> getPrecalculated(Set<Protein> proteins, Map<String, SignatureLibraryRelease> analysisJobMap) {
        // Check if the precalc service is configure and available.
        if (!preCalcMatchClient.isConfigured()) {
            return null;
        }

        String lookupMessageStatus = "Checking lookup client and server are in sync";
        try {
            // Only proceed if the lookup client and server are in sync
            if (!isSynchronised()) {
                return null;
            }
            // Then, check if the MD5s have been precalculated
            String[] md5s = new String[proteins.size()];
            // Map for looking up proteins by MD5 efficiently.
            final Map<String, Protein> md5ToProteinMap = new HashMap<String, Protein>(proteins.size());
            int i = 0;
            // Both populate the lookup map and also create the array of MD5s to query the service.
            for (Protein protein : proteins) {
                md5ToProteinMap.put(protein.getMd5().toUpperCase(), protein);
                md5s[i++] = protein.getMd5().toUpperCase();
            }
            lookupMessageStatus = "Check MD5s of proteins analysed previously";
            final List<String> analysedMd5s = preCalcMatchClient.getMD5sOfProteinsAlreadyAnalysed(md5s);

            // Check if NONE have been pre-calculated - if so, return empty set.
            if (analysedMd5s == null || analysedMd5s.size() == 0) {
                return Collections.emptySet();
            }

            // Create a Set of proteins that have been precalculated - this is what will end up being returned.
            final Set<Protein> precalculatedProteins = new HashSet<Protein>(analysedMd5s.size());

            // For the MD5s of proteins that have been pre-calculated, retrieve match data and populate the proteins.
            md5s = new String[analysedMd5s.size()];
            i = 0;
            for (String md5 : analysedMd5s) {
                final String md5Upper = md5.toUpperCase();
                md5s[i++] = md5Upper;
                precalculatedProteins.add(md5ToProteinMap.get(md5Upper));
            }
//            Utilities.verboseLog(110, "precalculatedProteins: "+ precalculatedProteins.toString());
            Long startTime = null;
            startTime = System.nanoTime();

            lookupMessageStatus = "Get matches of proteins analysed previously";
//            final KVSequenceEntryXML kvSequenceEntryXML = preCalcMatchClient.getMatches(md5s);
            final KVSequenceEntryXML kvSequenceEntryXML = getMatchesFromLookup(md5s);
//            Utilities.verboseLog(110, "berkeleyMatchXML: " +berkeleyMatchXML.getMatches().toString());

            //if null is returned from the lookupmatch then may need to be calculated
            if (kvSequenceEntryXML == null){
                Utilities.verboseLog(110, "For this batch, calculate the matches locally - analysedMd5s.size =  " + analysedMd5s.size());
                Utilities.verboseLog(110, "totalLookedup though: " +  totalLookedup);
                return Collections.emptySet();
	        }
            totalLookedup =	totalLookedup + analysedMd5s.size();
            Utilities.verboseLog(110, "totalLookedup: " +  totalLookedup);
            long timetaken = System.nanoTime() - startTime;
            long lookupTimeMillis = 0;
            if (timetaken > 0) {
                lookupTimeMillis = timetaken / 1000000;
            }

            Utilities.verboseLog(110, "Time to lookup " + kvSequenceEntryXML.getMatches().size() + " matches for " + md5s.length + " proteins: " + lookupTimeMillis + " millis");

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Time to lookup " + kvSequenceEntryXML.getMatches().size() + " matches for " + md5s.length + " proteins: " + lookupTimeMillis + " millis");
            }
            startTime = System.nanoTime();
            // Check if the analysis versions are consistent and then proceed
            boolean includeCDDorSFLD = includeCDDorSFLD(analysisJobMap);
            KVSequenceEntryXML kvSitesSequenceEntryXML = null;
            if(includeCDDorSFLD){
                Utilities.verboseLog(1100, "lookup Sites ... ");
                kvSitesSequenceEntryXML = getSitesFromLookup(md5s);
                Utilities.verboseLog(1100, "lookup Sites XML:" + kvSitesSequenceEntryXML.toString());
            }
            if (isAnalysisVersionConsistent(precalculatedProteins, kvSequenceEntryXML.getMatches(), analysisJobMap)) {
//                Utilities.verboseLog(110, "Analysis versions ARE Consistent" );
                lookupStoreToI5ModelDAO.populateProteinMatches(precalculatedProteins, kvSequenceEntryXML.getMatches(), kvSitesSequenceEntryXML.getMatches(), analysisJobMap, includeCDDorSFLD);
            } else {
                // If the member database version at lookupmatch service is different  from the analysis version in
                // interproscan, then disable the lookup match service for this batch (return null precalculatedProteins )
                Utilities.verboseLog(110, "Analysis versions NOT Consistent");
                return null;
            }
            timetaken = System.nanoTime() - startTime;
            lookupTimeMillis = 0;
            if (timetaken > 0) {
                lookupTimeMillis = timetaken / 1000000;
            }
            Utilities.verboseLog(110, "Time to convert to i5 matches " + kvSequenceEntryXML.getMatches().size() + " matches for " + md5s.length + " proteins: " + lookupTimeMillis + " millis");

            return precalculatedProteins;

        } catch (Exception e) {
            hostAvailabilityCheck(preCalcMatchClient.getUrl());
            displayLookupError(e, lookupMessageStatus);
            return null;
        }

    }

    public KVSequenceEntryXML getMatchesFromLookup(String... md5s) throws InterruptedException{
        int count = 0;
        int maxTries = 4;
        while(true) {
            try {
                KVSequenceEntryXML kvSequenceEntryXML = preCalcMatchClient.getMatches(md5s);
                return kvSequenceEntryXML;
            } catch (UnmarshallingFailureException e) {  //    also covers    UnmarshalException (JAXBException e) {
                // handle exception
                try {
                        Thread.sleep(10 * 1000);  //wait for 10 seconds before trying again
                }catch (InterruptedException exc){
                        throw exc;
                }
                if (++count == maxTries) {
		            return null;
		        }
            } catch (IOException e) {
                // handle exception
                if (++count == maxTries) break;
            }catch (Exception e) {
                if (e instanceof JAXBException){
                    try {
                        Thread.sleep(10 * 1000);  //wait for 10 seconds before trying again
                    }catch (InterruptedException exc){
                        throw exc;
                    }
                    if (++count == maxTries) break;
                }else {
                    LOGGER.warn("Lookupmatch server: encountered an unspecific error while getting matches ");
                    throw e;
                }
            }
        }
        return null;
    }


    public KVSequenceEntryXML getSitesFromLookup(String... md5s) throws InterruptedException {
        int count = 0;
        int maxTries = 4;
        while (true) {
            try {
                KVSequenceEntryXML kvSequenceEntryXML = preCalcMatchClient.getSites(md5s);
                return kvSequenceEntryXML;
            } catch (UnmarshallingFailureException e) {  //    also covers    UnmarshalException (JAXBException e) {
                // handle exception
                try {
                    Thread.sleep(10 * 1000);  //wait for 10 seconds before trying again
                } catch (InterruptedException exc) {
                    throw exc;
                }
                if (++count == maxTries) {
                    return null;
                }
            } catch (IOException e) {
                // handle exception
                if (++count == maxTries) break;
            } catch (Exception e) {
                if (e instanceof JAXBException) {
                    try {
                        Thread.sleep(10 * 1000);  //wait for 10 seconds before trying again
                    } catch (InterruptedException exc) {
                        throw exc;
                    }
                    if (++count == maxTries) break;
                } else {
                    LOGGER.warn("Lookupmatch server: encountered an unspecific error while getting matches ");
                    throw e;
                }
            }
        }
        return null;
    }



    /**
     * Utility method to confirm if this service is working.
     *
     * @return true if the service is working.
     */
    public boolean isConfigured() {
        return preCalcMatchClient.isConfigured();
    }

    /**
     * If the client and the server are based on the same version of interproscan
     * return true, otherwise return false
     */
    public boolean isSynchronised() throws IOException {
        // checks if the interpro data version is the same
        // codes version differences are ignored
        // TODO make this more robust - currently assumes everyhting after the last dash is the interpro version
        String serverVersion = preCalcMatchClient.getServerVersion();
        int finalDashIndex = interproscanVersion.lastIndexOf("-");
        String interproDataVersion = interproscanVersion.substring(finalDashIndex);
        if (!serverVersion.endsWith(interproDataVersion)) {
            displayLookupSynchronisationError(interproscanVersion, serverVersion);
            return false;
        }

        return true;

    }

    /**
     * If the member database version at lookupmatch service is different  from the analysis version in
     * interproscan, then disable the lookup match service for this batch
     *
     * @param preCalculatedProteins
     * @param kvSequenceEntries
     * @param analysisJobMap
     * @return
     */
    public boolean isAnalysisVersionConsistent(Set<Protein> preCalculatedProteins, List<KVSequenceEntry> kvSequenceEntries, Map<String, SignatureLibraryRelease> analysisJobMap) {
        // Collection of BerkeleyMatches of different kinds.
        Map<String, String> lookupAnalysesMap = new HashMap<String, String>();
        for (KVSequenceEntry kvSequenceEntry : kvSequenceEntries) {
            String proteinMD5 = kvSequenceEntry.getProteinMD5();
            Set<String> sequenceHits = kvSequenceEntry.getSequenceHits();
            for (String sequenceHit :sequenceHits) {
                LOGGER.debug("csvMatch:" + sequenceHit);
                SimpleLookupMatch simpleMatch = new SimpleLookupMatch(proteinMD5, sequenceHit);
                LOGGER.debug("simpleMatch " + simpleMatch.toString());
                String signatureLibraryReleaseVersion = simpleMatch.getSigLibRelease();
                final SignatureLibrary sigLib = SignatureLibraryLookup.lookupSignatureLibrary(simpleMatch.getSignatureLibraryName());
                lookupAnalysesMap.put(sigLib.getName().toUpperCase(), signatureLibraryReleaseVersion);
            }
        }
        for (String analysisJobName : analysisJobMap.keySet()) {
            if (lookupAnalysesMap.containsKey(analysisJobName.toUpperCase())) {
                String lookUpMatchAnalaysVersion = lookupAnalysesMap.get(analysisJobName.toUpperCase());
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("analysis: " + analysisJobName + " lookUpMatchAnalaysiVersion: "
                            + lookUpMatchAnalaysVersion + " analysisJobName: " + analysisJobName + " analysisJobVersion: " + analysisJobMap.get(analysisJobName).getVersion());
                }
                if (!lookUpMatchAnalaysVersion.equals(analysisJobMap.get(analysisJobName).getVersion())) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Different versions of  " + analysisJobName + " running ");
                    }
                    return false;
                }
            }
        }
        return true;
    }

    /**
     *  include fetching CDD or SFLD sites
     *
     * @param analysisJobMap
     * @return
     */
    private boolean includeCDDorSFLD(Map<String, SignatureLibraryRelease> analysisJobMap){
        for (SignatureLibraryRelease sigLibrelease : analysisJobMap.values()) {
            if (sigLibrelease.getLibrary().getName().startsWith("CDD") ||
                    sigLibrelease.getLibrary().getName().startsWith("SFLD")){
                return true;
            }
        }

        return false;
    }

    private void displayLookupError(Exception e, String lookupMessageStatus) {
        /* Barf out - the user wants pre-calculated, but this is not available - tell them what action to take. */

        if (timeLookupError != null) {
            if (!fixedTimeLapsed(timeLookupError)) {
                return;
            }
        }

        timeLookupError = System.currentTimeMillis();

//        LOGGER.warn(e);
//        e.printStackTrace();
//        LOGGER.warn(e.toString());
        LOGGER.warn("Problem with lookup service while on the step: " + lookupMessageStatus);

        LOGGER.warn("\n\n" +
                "The following problem was encountered by the pre-calculated match lookup service:\n" +
                e.getMessage() + "\n" +
                "Pre-calculated match lookup service failed - analysis proceeding to run locally\n" +
                "============================================================\n\n" +
                "The pre-calculated match lookup service has been configured in the interproscan.properties file.  \n" +
                "  precalculated match lookup service url : " + preCalcMatchClient.getUrl() + "\n" +
                "  precalculated match lookup service proxy host : " + preCalcMatchClient.getProxyHost() + "  proxy port : " + preCalcMatchClient.getProxyPort() + "\n\n" +
                "Unfortunately the web service has failed. Check the configuration of this service\n" +
                "in the interproscan.properties file and, if necessary, set the following property to look like this:\n\n" +
                "precalculated.match.lookup.service.url=\n\n" +
                "If the problem persists, check if this is a firewall or proxy issue. If it is a proxy issue, then setting \n" +
                "the following property in the interproscan.properties file should work:\n\n" +
                "precalculated.match.lookup.service.proxy.host=\n" +
                "precalculated.match.lookup.service.proxy.port=\n\n" +
                "If this still does not work please inform the InterPro team of this error\n" +
                "by sending an email to:\n\ninterhelp@ebi.ac.uk\n\n" +
                "In the meantime, the analysis will continue to run locally.\n\n");


    }

    private void displayLookupSynchronisationError(String clientVersion, String serverVersion) {

        if (timeLookupSynchronisationError != null) {
            if (!fixedTimeLapsed(timeLookupSynchronisationError)) {
                return;
            }
        }

        timeLookupSynchronisationError = System.currentTimeMillis();

        if (!Utilities.lookupMatchVersionProblemMessageDisplayed) {
            LOGGER.warn(
                    "\n\nThe version of InterProScan you are using is " + clientVersion + "\n" +
                            "The version of the lookup service you are using is " + serverVersion + "\n" +
                            "As the data in these versions is not the same, you cannot use this match lookup service.\n" +
                            "InterProScan will now run locally\n" +
                            "If you would like to use the match lookup service, you have the following options:\n" +
                            "i) Download the newest version of InterProScan5 from our FTP site by following the instructions on:\n" +
                            "   https://www.ebi.ac.uk/interpro/interproscan.html\n" +
                            "ii) Download the match lookup service for your version of InterProScan from our FTP site and install it locally.\n" +
                            "    You will then need to edit the following property in your configuration file to point to your local installation:\n" +
                            "    precalculated.match.lookup.service.url=\n\n" +
                            "In the meantime, the analysis will continue to run locally.\n\n");

            Utilities.lookupMatchVersionProblemMessageDisplayed = true;
        }
    }

    private Boolean fixedTimeLapsed(Long previousTime) {
        Long fixedTimeBetweenDisplays = 10L;
        Long hoursSince = null;
        Long timeLapse = System.currentTimeMillis() - previousTime;
        if (timeLapse > 0) {
            hoursSince = timeLapse / (60 * 60 * 1000);
            //default is display error message every 10 hours
            if (hoursSince > fixedTimeBetweenDisplays) {
                return true;
            }
        }

        return false;
    }

    public boolean hostAvailabilityCheck(String SERVER_ADDRESS) {
        boolean available = true;
        String hostAvailabilityMessage = "";
        Boolean usingProxy = false;
        URL url = null;
        HttpURLConnection httpConn = null;
        try {
            url = new URL(SERVER_ADDRESS);
            try {
                httpConn = (HttpURLConnection) url.openConnection();
                httpConn.setInstanceFollowRedirects(false);
                httpConn.setRequestMethod("HEAD");
                usingProxy = httpConn.usingProxy();
                httpConn.connect();
                hostAvailabilityMessage = "accessible - code: " + httpConn.getResponseCode();
            }catch (NoRouteToHostException e){
                available = false;
                hostAvailabilityMessage = "not avaliable, NoRouteToHostException : " + e.getMessage();
            } catch (java.net.ConnectException e) {
                available = false;
                hostAvailabilityMessage = "not avaliable, ConnectException : " + e.getMessage();
            } catch (IOException e) { // io exception, service probably not running
                available = false;
                hostAvailabilityMessage = "not avaliable, IOException : " + e.getMessage();
            } catch (Exception e) { // exception, service probably not running
                available = false;
                hostAvailabilityMessage = "not avaliable, Exception : " + e.getMessage();
            } finally {
                if (httpConn != null) {
                    httpConn.disconnect();
                }
            }
        } catch (MalformedURLException e) {
            available = false;
            hostAvailabilityMessage =  " not avaliable, MalformedURLException : " + e.getMessage();
        }

        hostAvailabilityMessage = "lookupUp service at " + SERVER_ADDRESS + " is " + hostAvailabilityMessage + ",  using proxy: " + usingProxy;

        LOGGER.warn(hostAvailabilityMessage);
        return available;
    }

    public boolean hostAvailabilityCheck2(String SERVER_ADDRESS, int TCP_SERVER_PORT) {
        boolean available = true;
        String hostAvailabilityMessage = "";
        try {
            Socket lookupSocket = new Socket(SERVER_ADDRESS, TCP_SERVER_PORT);
            if (lookupSocket.isConnected()) {
                lookupSocket.close();
                hostAvailabilityMessage = "lookupUp service is available and accessible";
            }
        } catch (UnknownHostException e) { // unknown host
            available = false;
            hostAvailabilityMessage = "lookupUp service is not avaliable, UnknownHostException : " + e.getMessage();
        } catch (IOException e) { // io exception, service probably not running
            available = false;
            hostAvailabilityMessage = "lookupUp service is not avaliable, IOException : " + e.getMessage();
        } catch (NullPointerException e) {
            available = false;
            hostAvailabilityMessage = "lookupUp service is not avaliable, NullPointerException : " + e.getMessage();
        }

        return available;
    }

}

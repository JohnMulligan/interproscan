package uk.ac.ebi.interpro.scan.persistence;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.transaction.annotation.Transactional;
import uk.ac.ebi.interpro.scan.model.FingerPrintsMatch;
import uk.ac.ebi.interpro.scan.model.Match;
import uk.ac.ebi.interpro.scan.model.Protein;
import uk.ac.ebi.interpro.scan.model.Signature;
import uk.ac.ebi.interpro.scan.model.raw.PrintsRawMatch;
import uk.ac.ebi.interpro.scan.model.raw.RawProtein;
import uk.ac.ebi.interpro.scan.model.helper.SignatureModelHolder;
import uk.ac.ebi.interpro.scan.util.Utilities;

import java.util.*;

/**
 * @author Phil Jones, EMBL-EBI
 * @version $Id$
 * @since 1.0
 */

public class PrintsFilteredMatchDAOImpl extends FilteredMatchDAOImpl<PrintsRawMatch, FingerPrintsMatch> {

    private static final Logger LOGGER = LogManager.getLogger(PrintsFilteredMatchDAOImpl.class.getName());

    /**
     * Sets the class of the model that the DOA instance handles.
     * Note that this has been set up to use constructor injection
     * because it makes it easy to sub-class GenericDAOImpl in a robust
     * manner.
     * <p/>
     * Model class specific sub-classes should define a no-argument constructor
     * that calls this constructor with the appropriate class.
     */
    public PrintsFilteredMatchDAOImpl() {
        super(FingerPrintsMatch.class);
    }

    /**
     * This is the method that should be implemented by specific FilteredMatchDAOImpl's to
     * persist filtered matches.
     *
     * @param filteredProteins      being the Collection of filtered RawProtein objects to persist
     * @param modelIdToSignatureMap a Map of signature accessions to Signature objects.
     * @param proteinIdToProteinMap a Map of Protein IDs to Protein objects
     */
    @Override
    @Transactional
    public void persist(Collection<RawProtein<PrintsRawMatch>> filteredProteins, Map<String, SignatureModelHolder> modelIdToSignatureMap, Map<String, Protein> proteinIdToProteinMap) {

        for (RawProtein<PrintsRawMatch> rawProtein : filteredProteins) {
            Protein protein = proteinIdToProteinMap.get(rawProtein.getProteinIdentifier());
            if (protein == null) {
                throw new IllegalStateException("Cannot store match to a protein that is not in database " +
                        "[protein ID= " + rawProtein.getProteinIdentifier() + "]");
            }
            Set<FingerPrintsMatch.FingerPrintsLocation> locations = null;
            String currentSignatureAc = null;
            SignatureModelHolder holder = null;
            Signature currentSignature = null;
            PrintsRawMatch lastRawMatch = null;

            // Need the matches sorted correctly so locations are grouped in the same match.
            final TreeSet<PrintsRawMatch> sortedMatches = new TreeSet<PrintsRawMatch>(PRINTS_RAW_MATCH_COMPARATOR);
            sortedMatches.addAll(rawProtein.getMatches());
            FingerPrintsMatch match = null;
            String signatureLibraryKey = null;
            Set <Match> proteinMatches =  new HashSet<>();
            for (PrintsRawMatch rawMatch : sortedMatches) {
                if (rawMatch == null) {
                    continue;
                }

                if (currentSignatureAc == null || !currentSignatureAc.equals(rawMatch.getModelId())) {
                    if (currentSignatureAc != null) {

                        // Not the first (because the currentSignatureAc is not null)
                        if (match != null) {
                            //entityManager.persist(match); // Persist the previous one...
                        }
                        match = new FingerPrintsMatch(currentSignature, currentSignatureAc, lastRawMatch.getEvalue(), lastRawMatch.getGraphscan(), locations);
                        //protein.addMatch(match);   // Sets the protein on the match.
                        proteinMatches.add(match);
                    }
                    // Reset everything
                    locations = new HashSet<FingerPrintsMatch.FingerPrintsLocation>();
                    currentSignatureAc = rawMatch.getModelId();
                    holder = modelIdToSignatureMap.get(currentSignatureAc);
                    currentSignature = holder.getSignature();
                    if (currentSignature == null) {
                        throw new IllegalStateException("Cannot find PRINTS signature " + currentSignatureAc + " in the database.");
                    }
                }
                locations.add(
                        new FingerPrintsMatch.FingerPrintsLocation(
                                rawMatch.getLocationStart(),
                                boundedLocationEnd(protein, rawMatch),
                                rawMatch.getPvalue(),
                                rawMatch.getScore(),
                                rawMatch.getMotifNumber()
                        )
                );
                lastRawMatch = rawMatch;
                if(signatureLibraryKey == null){
                    signatureLibraryKey = currentSignature.getSignatureLibraryRelease().getLibrary().getName();
                }
            }
            // Don't forget the last one!
            if (lastRawMatch != null) {
                match = new FingerPrintsMatch(currentSignature, currentSignatureAc, lastRawMatch.getEvalue(), lastRawMatch.getGraphscan(), locations);
                //protein.addMatch(match);   // Sets the protein on the match.
                proteinMatches.add(match);
                //entityManager.persist(match);
            }
            final String dbKey = Long.toString(protein.getId()) + signatureLibraryKey;
            //Utilities.verboseLog(1100, "persisted matches in kvstore for key: " + dbKey);

            if (proteinMatches != null && ! proteinMatches.isEmpty()) {
                //Utilities.verboseLog(1100, "persisted matches in kvstore for key: " + dbKey + " : " + proteinMatches.size());
                for(Match i5Match: proteinMatches){
                    //try update with cross refs etc
                    updateMatch(i5Match);
                }
                matchDAO.persist(dbKey, proteinMatches);
            }
        }
    }

    public static final Comparator<PrintsRawMatch> PRINTS_RAW_MATCH_COMPARATOR = new Comparator<PrintsRawMatch>() {

        /**
         * This comparator is CRITICAL to the working of PRINTS post-processing, so it has been defined
         * here rather than being the 'natural ordering' of PrintsRawMatch objects so it is not
         * accidentally modified 'out of context'.
         *
         * Sorts the raw matches by:
         *
         * evalue (best first)
         * model accession
         * motif number (ascending)
         * location start
         * location end
         *
         * @param o1 the first PrintsRawMatch to be compared.
         * @param o2 the second PrintsRawMatch to be compared.
         * @return a negative integer, zero, or a positive integer as the
         *         first PrintsRawMatch is less than, equal to, or greater than the
         *         second PrintsRawMatch.
         */
        @Override
        public int compare(PrintsRawMatch o1, PrintsRawMatch o2) {
            int comparison = o1.getSequenceIdentifier().compareTo(o2.getSequenceIdentifier());
            if (comparison == 0) {
                if (o1.getEvalue() < o2.getEvalue()) comparison = -1;
                else if (o1.getEvalue() > o2.getEvalue()) comparison = 1;
            }
            if (comparison == 0) {
                comparison = o1.getModelId().compareTo(o2.getModelId());
            }
            if (comparison == 0) {
                if (o1.getMotifNumber() < o2.getMotifNumber()) comparison = -1;
                else if (o1.getMotifNumber() > o2.getMotifNumber()) comparison = 1;
            }
            if (comparison == 0) {
                if (o1.getLocationStart() < o2.getLocationStart()) comparison = -1;
                else if (o1.getLocationStart() > o2.getLocationStart()) comparison = 1;
            }
            if (comparison == 0) {
                if (o1.getLocationEnd() < o2.getLocationEnd()) comparison = -1;
                else if (o1.getLocationEnd() > o2.getLocationEnd()) comparison = 1;
            }
            return comparison;
        }
    };
}

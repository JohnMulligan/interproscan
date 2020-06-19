package uk.ac.ebi.interpro.scan.io.ntranslate;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import org.apache.logging.log4j.LogManager;
import uk.ac.ebi.interpro.scan.model.NucleotideSequenceStrand;
import uk.ac.ebi.interpro.scan.model.OpenReadingFrame;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Description line parser for GetOrf formatted FASTA files.
 *
 * @author Maxim Scheremetjew, EMBL-EBI, InterPro
 * @author Gift Nuka
 * @version $Id$
 * @since 1.0-SNAPSHOT
 */
public class ORFDescriptionLineParser {

    private static final Logger LOGGER = LogManager.getLogger(ORFDescriptionLineParser.class.getName());

    private static final Pattern GETORF_DESCRIPTION_PATTERN = Pattern.compile("\\[(\\d+)\\s+\\-\\s+(\\d+)]\\s*(\\(REVERSE SENSE\\))?\\s*(.*)$");

    private static final Pattern TRANSLATE_DESCRIPTION_PATTERN = Pattern.compile("^(\\S+)\\s+(\\S+)\\s+(.*)$");

    public OpenReadingFrame createORFFromParsingResult(String description) {
        final Matcher matcher = TRANSLATE_DESCRIPTION_PATTERN.matcher(description);
        if (LOGGER.isDebugEnabled()) LOGGER.debug("DESCRIPTION: '" + description + "'");
        if (matcher.find()) {
            if (LOGGER.isDebugEnabled()) LOGGER.debug("Matched!");
            String coords = matcher.group(2);
            String [] coordsPair = coords.replace("coords=", "").split("\\.\\.");
            //check if reverse or not
            final int start = Integer.parseInt(coordsPair[0]);
            final int end = Integer.parseInt(coordsPair[1]);
            boolean reverseSense = start >= end? true:false;
            if (! reverseSense) {
                return new OpenReadingFrame(start, end, NucleotideSequenceStrand.SENSE);
            } else {
                return new OpenReadingFrame(end, start, NucleotideSequenceStrand.ANTISENSE);
            }
        } else {
            LOGGER.error("Description line in getorf output (fasta) file not as expected:" + description);
            throw new IllegalStateException("Description line in getorf output (fasta) file not as expected. See fine logging for details.");
        }
    }
}

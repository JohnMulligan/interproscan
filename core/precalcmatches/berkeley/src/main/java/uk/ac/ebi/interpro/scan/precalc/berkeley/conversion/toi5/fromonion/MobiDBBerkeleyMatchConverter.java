package uk.ac.ebi.interpro.scan.precalc.berkeley.conversion.toi5.fromonion;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import uk.ac.ebi.interpro.scan.model.MobiDBMatch;
import uk.ac.ebi.interpro.scan.model.RPSBlastMatch;
import uk.ac.ebi.interpro.scan.model.Signature;
import uk.ac.ebi.interpro.scan.precalc.berkeley.conversion.toi5.BerkeleyMatchConverter;
import uk.ac.ebi.interpro.scan.precalc.berkeley.model.BerkeleyLocation;
import uk.ac.ebi.interpro.scan.precalc.berkeley.model.BerkeleyMatch;

import java.util.HashSet;
import java.util.Set;

/**
 * Converts a BerkeleyMatch to a RPSBlast Match.
 *
 * @author Gift Nuka
 * @date 20/05/2016
 * @version $Id$
 * @since 5.19.0-SNAPSHOT
 */
public class MobiDBBerkeleyMatchConverter extends BerkeleyMatchConverter<MobiDBMatch> {

    private static final Logger LOG = LogManager.getLogger(MobiDBBerkeleyMatchConverter.class.getName());

    public MobiDBMatch convertMatch(BerkeleyMatch berkeleyMatch, Signature signature) {

        Set<MobiDBMatch.MobiDBLocation> locations = new HashSet<>(berkeleyMatch.getLocations().size());

        for (BerkeleyLocation location : berkeleyMatch.getLocations()) {
            locations.add(new MobiDBMatch.MobiDBLocation(
                    valueOrZero(location.getStart()),
                    valueOrZero(location.getEnd()),
                    location.getSeqFeature()
            ));
        }

        return new MobiDBMatch(signature, berkeleyMatch.getSignatureModels(), locations);
    }

}

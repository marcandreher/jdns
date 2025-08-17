package de.herpersolutions.engine;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import de.herpersolutions.Zones.JsonRecord;
import de.herpersolutions.Zones.JsonZone;
import de.herpersolutions.Zones.ZoneStore;

public class AuthoritativeEngine {
    private final ZoneStore store;
    // Index: fqdn -> list of records
    private final ConcurrentMap<Name, List<org.xbill.DNS.Record>> recordIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<Name, SOARecord> soaByZone = new ConcurrentHashMap<>();
    private final ConcurrentMap<Name, List<NSRecord>> nsByZone = new ConcurrentHashMap<>();

    private static final Logger logger = LoggerFactory.getLogger(AuthoritativeEngine.class);

    public AuthoritativeEngine(ZoneStore store) throws TextParseException, UnknownHostException {
        this.store = store;
        rebuildIndex();
    }

    void rebuildIndex() throws TextParseException, UnknownHostException {
        recordIndex.clear();
        soaByZone.clear();
        nsByZone.clear();
        for (JsonZone z : store.zones.values())
            buildZone(z);
    }

    private void buildZone(JsonZone z) throws TextParseException, UnknownHostException {
        Name origin = z.originName();
        long ttl = Math.max(0, z.getDefaultTtl());

        // SOA
        Name mname = z.getNs().isEmpty() ? Name.fromString("ns1." + z.getOrigin(), Name.root)
                : Name.fromString(ensureDot(z.getNs().get(0)), Name.root);
        Name rname = Name.fromString(ensureDot(z.getAdmin()), Name.root);
        SOARecord soa = new SOARecord(origin, DClass.IN, ttl, mname, rname, z.getSerial(), z.getRefresh(), z.getRetry(),
                z.getExpire(),
                z.getMinimum());
        soaByZone.put(origin, soa);

        // NS (authoritative)
        List<NSRecord> nsRecs = new ArrayList<>();
        for (String nsHost : z.getNs()) {
            Name target = Name.fromString(ensureDot(nsHost), Name.root);
            NSRecord ns = new NSRecord(origin, DClass.IN, ttl, target);
            nsRecs.add(ns);
            addRecord(ns);
        }
        nsByZone.put(origin, nsRecs);

        // Other records
        for (JsonRecord jr : z.getRecords()) {
            long rttl = jr.getTtl() >= 0 ? jr.getTtl() : ttl;
            Name owner = toOwnerName(jr.getName(), origin);
            String t = jr.getType() == null ? "A" : jr.getType().toUpperCase(Locale.ROOT);
            switch (t) {
                case "A":
                    addRecord(new ARecord(owner, DClass.IN, rttl, InetAddress.getByName(jr.getData())));
                    break;
                case "AAAA":
                    addRecord(new AAAARecord(owner, DClass.IN, rttl, InetAddress.getByName(jr.getData())));
                    break;
                case "CNAME":
                    addRecord(new CNAMERecord(owner, DClass.IN, rttl,
                            Name.fromString(ensureDot(jr.getData()), Name.root)));
                    break;
                case "TXT":
                    addRecord(new TXTRecord(owner, DClass.IN, rttl, jr.getData()));
                    break;
                case "MX": {
                    int pref = jr.getPriority() == null ? 10 : jr.getPriority();
                    Name target = Name.fromString(ensureDot(jr.getData()), Name.root);
                    addRecord(new MXRecord(owner, DClass.IN, rttl, pref, target));
                    break;
                }
                case "NS": {
                    Name target = Name.fromString(ensureDot(jr.getData()), Name.root);
                    addRecord(new NSRecord(owner, DClass.IN, rttl, target));
                    break;
                }
                case "SOA": // usually derived from zone; ignore explicit
                    break;
                default:
                    System.err.println("Unsupported RR type in JSON: " + t + ", skipping.");
            }
        }
    }

    private static Name toOwnerName(String name, Name origin) throws TextParseException {
        if (name == null || name.equals("@"))
            return origin;
        if (name.endsWith("."))
            return Name.fromString(name, Name.root);
        return Name.fromString(name + "." + origin, Name.root);
    }

    private static String ensureDot(String s) {
        return s.endsWith(".") ? s : s + ".";
    }

    private void addRecord(org.xbill.DNS.Record r) {
        recordIndex.compute(r.getName(), (k, v) -> {
            if (v == null)
                v = new ArrayList<>();
            v.add(r);
            return v;
        });
    }

    public Message answer(Message query, InetAddress clientIp) {

        Header qh = query.getHeader();
        org.xbill.DNS.Record qrec = query.getQuestion();
        Message response = new Message();
        response.setHeader(new Header(qh.getID()));
        response.getHeader().setFlag(Flags.QR);
        response.getHeader().setFlag(Flags.AA); // authoritative
        response.addRecord(qrec, Section.QUESTION);

        try {
            Name qname = qrec.getName();
            int qtype = qrec.getType();

            JsonZone z = store.getZoneByName(qname);
            if (z == null) {
                response.getHeader().setRcode(Rcode.NXDOMAIN);
                SOARecord soa = closestSoa(qname);
                if (soa != null)
                    response.addRecord(soa, Section.AUTHORITY);
                logger.info("Query ({}) [{}] [{}] | FAILURE (no matching zone)", String.valueOf(qrec.getName()), clientIp.getHostAddress(), Type.string(qrec.getType()));
                return response;
            }

            Name origin = z.originName();
            List<org.xbill.DNS.Record> answers = match(qname, qtype);

            if (answers.isEmpty()) {
                // NODATA
                response.getHeader().setRcode(Rcode.NOERROR);
                SOARecord soa = soaByZone.get(origin);
                if (soa != null)
                    response.addRecord(soa, Section.AUTHORITY);
                logger.info("Query ({}) [{}] [{}] | FAILURE (no answer records)", String.valueOf(qrec.getName()), clientIp.getHostAddress(), Type.string(qrec.getType()));
                return response;
            }

            for (org.xbill.DNS.Record r : answers)
                response.addRecord(r, Section.ANSWER);

            // Authority: zone NS
            List<NSRecord> ns = nsByZone.getOrDefault(origin, Collections.emptyList());
            for (NSRecord nr : ns)
                response.addRecord(nr, Section.AUTHORITY);

            // Additional: glue for NS and MX targets
            Set<Name> extraNames = new LinkedHashSet<>();
            for (org.xbill.DNS.Record r : answers)
                collectAdditionalTargets(r, extraNames);
            for (NSRecord nr : ns)
                extraNames.add(nr.getTarget());
            for (Name n : extraNames) {
                for (org.xbill.DNS.Record rr : match(n, Type.A))
                    response.addRecord(rr, Section.ADDITIONAL);
                for (org.xbill.DNS.Record rr : match(n, Type.AAAA))
                    response.addRecord(rr, Section.ADDITIONAL);
            }

            response.getHeader().setRcode(Rcode.NOERROR);
            logger.info("Query ({}) [{}] [{}] | SUCCESS", String.valueOf(qrec.getName()), clientIp.getHostAddress(), Type.string(qrec.getType()));
            return response;
        } catch (Exception e) {
            logger.info("Query ({}) [{}] [{}] | FAILURE (exception)", String.valueOf(qrec.getName()), clientIp.getHostAddress(), Type.string(qrec.getType()));
            response.getHeader().setRcode(Rcode.SERVFAIL);
            return response;
        }
    }

    private SOARecord closestSoa(Name qname) {
        Name n = qname;
        while (n.labels() > 1) {
            SOARecord soa = soaByZone.get(n);
            if (soa != null)
                return soa;
            n = new Name(n, 1); // strip leftmost label
        }
        return null;
    }

    private List<org.xbill.DNS.Record> match(Name name, int type) {
        List<org.xbill.DNS.Record> list = recordIndex.getOrDefault(name, Collections.emptyList());
        // handle ANY
        if (type == Type.ANY)
            return list;
        // handle CNAME chain: if QTYPE not found but CNAME exists, return CNAME only
        List<org.xbill.DNS.Record> exact = list.stream().filter(r -> r.getType() == type).collect(Collectors.toList());
        if (!exact.isEmpty())
            return exact;
        for (org.xbill.DNS.Record r : list)
            if (r.getType() == Type.CNAME)
                return Collections.singletonList(r);
        return Collections.emptyList();
    }

    private void collectAdditionalTargets(org.xbill.DNS.Record r, Set<Name> out) {
        if (r instanceof MXRecord)
            out.add(((MXRecord) r).getTarget());
        if (r instanceof NSRecord)
            out.add(((NSRecord) r).getTarget());
        if (r instanceof CNAMERecord)
            out.add(((CNAMERecord) r).getTarget());
    }
}

package org.bgi.flexlab.gaea.data.structure.bam;

import htsjdk.samtools.util.StringUtil;

import java.util.ArrayList;

import org.bgi.flexlab.gaea.data.structure.location.GenomeLocation;
import org.bgi.flexlab.gaea.data.structure.location.GenomeLocationParser;
import org.bgi.flexlab.gaea.data.structure.reference.ChromosomeInformationShare;

public class GaeaSamRecordBin {
	private ArrayList<GaeaSamRecord> records = null;
	private byte[] refBases = null;
	private GenomeLocation location = null;
	private int REFERENCE_EXTEND = 30;
	private GenomeLocationParser parser = null;
	
	public GaeaSamRecordBin(GenomeLocationParser parser,int extend) {
		setParser(parser);
		setExtend(extend);
	}

	public GaeaSamRecordBin(GenomeLocationParser parser) {
		setParser(parser);
	}

	public void setParser(GenomeLocationParser parser) {
		this.parser = parser;
	}
	
	public void setExtend(int extend){
		this.REFERENCE_EXTEND = extend;
	}

	public void add(GaeaSamRecord read) {
		GenomeLocation loc = parser.createGenomeLocation(read);

		if (location == null)
			location = loc;
		else if (loc.getStop() > location.getStop()) {
			location = parser.createGenomeLocation(location.getContig(),
					location.getStart(), loc.getStop());
		}

		records.add(read);
	}

	public GenomeLocation getLocation() {
		return this.location;
	}

	public byte[] getReference(ChromosomeInformationShare chr,
			GenomeLocationParser parser) {
		if (refBases == null) {
			int start = location.getStart() - REFERENCE_EXTEND;
			int end = location.getStop() + REFERENCE_EXTEND;
			location = parser.createGenomeLocation(location.getContig(), start,
					end);
			refBases = chr.getBytes(start - 1, end - 1);

			StringUtil.toUpperCase(refBases);
		}
		return this.refBases;
	}

	public ArrayList<GaeaSamRecord> getReads() {
		return this.records;
	}

	public int size() {
		return records.size();
	}

	public void clear() {
		records.clear();
		refBases = null;
		location = null;
	}
}
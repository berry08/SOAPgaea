package org.bgi.flexlab.gaea.framework.tools.mapreduce;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMReadGroupRecord;
import htsjdk.samtools.SAMRecord;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.Mapper;
import org.bgi.flexlab.gaea.data.exception.FileNotExistException;
import org.bgi.flexlab.gaea.data.mapreduce.input.bed.RegionHdfsParser;
import org.bgi.flexlab.gaea.data.mapreduce.input.header.SamHdfsFileHeader;
import org.bgi.flexlab.gaea.data.mapreduce.writable.SamRecordWritable;
import org.bgi.flexlab.gaea.data.mapreduce.writable.WindowsBasedWritable;
import org.bgi.flexlab.gaea.data.structure.bam.filter.util.SamRecordFilter;
import org.bgi.flexlab.gaea.util.SamRecordUtils;

public abstract class WindowsBasedMapper<VALUEOUT extends Writable> extends
		Mapper<LongWritable, SamRecordWritable, WindowsBasedWritable, VALUEOUT> {

	public final static String WINDOWS_SIZE = "windows.size";
	public final static String WINDOWS_EXTEND_SIZE = "windows.extend.size";
	public final static String MULTIPLE_SAMPLE = "multiple.sample";
	public final static String SAM_RECORD_FILTER = "sam.record.filter";
	public final static String REFERENCE_REGION = "reference.region.bed";
	public final static String UNMAPPED_REFERENCE_NAME = "UNMAPPED";

	protected int windowsSize;
	protected int windowsExtendSize;
	protected boolean multiSample;
	protected SAMFileHeader header = null;

	protected WindowsBasedWritable keyout = new WindowsBasedWritable();
	private SamRecordFilter recordFilter = null;
	private RegionHdfsParser region = null;
	protected VALUEOUT outputValue;

	private HashMap<String, Integer> sampleIDs = null;

	abstract void setOutputValue(SAMRecord samRecord);
	abstract void initOutputVaule();

	@Override
	protected void setup(Context context) throws IOException, InterruptedException {
		Configuration conf = context.getConfiguration();
		windowsSize = conf.getInt(WINDOWS_SIZE, 10000);
		windowsExtendSize = conf.getInt(WINDOWS_EXTEND_SIZE, 500);
		multiSample = conf.getBoolean(MULTIPLE_SAMPLE, false);
		initOutputVaule();

		header = SamHdfsFileHeader.getHeader(conf);

		if (header == null) {
			String[] filename = context.getInputSplit().toString().split("/|:");
			throw new FileNotExistException.MissingHeaderException(filename[filename.length - 2]);
		}

		sampleIDs = new HashMap<String, Integer>();

		List<SAMReadGroupRecord> list = header.getReadGroups();
		for (int i = 0; i < list.size(); i++) {
			sampleIDs.put(list.get(i).getSample(), i);
		}

		String className = conf.get(SAM_RECORD_FILTER);
		if (className == null) {
			recordFilter = new SamRecordFilter.DefaultSamRecordFilter();
		} else {
			try {
				recordFilter = (SamRecordFilter) (Class.forName(className).newInstance());
			} catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
				throw new RuntimeException(e);
			}
		}

		if (conf.get(REFERENCE_REGION) != null) {
			region = new RegionHdfsParser();
			region.parseBedFileFromHDFS(conf.get(REFERENCE_REGION), false);
		}
	}

	protected int[] getExtendPosition(int start, int end, int length) {
		int[] winNum = new int[3];

		winNum[1] = (int) (((start - windowsExtendSize) > 0 ? (start - windowsExtendSize) : 0) / windowsSize);
		winNum[0] = start / windowsSize;
		winNum[2] = (int) (((end + windowsExtendSize) > length ? length : (end + windowsExtendSize)) / windowsSize);

		return winNum;
	}

	protected void setKey(SAMRecord sam, int winNum) {
		setKey(sam.getReadGroup().getSample(), sam.getReferenceIndex(), winNum, sam.getAlignmentStart());
	}

	protected void setKey(String sampleName, int chrIndex, int winNum, int position) {
		int sampleID = 0;
		if (multiSample) {
			if (!sampleIDs.containsKey(sampleName))
				throw new RuntimeException("header isn't contains sample " + sampleName);
			sampleID = sampleIDs.get(sampleName);
		}
		keyout.set(sampleID, chrIndex, winNum, position);
	}

	@Override
	protected void map(LongWritable key, SamRecordWritable value, Context context)
			throws IOException, InterruptedException {
		SAMRecord sam = value.get();
		if (recordFilter.filter(sam, region)) {
			return;
		}
		setOutputValue(sam);

		if (SamRecordUtils.isUnmapped(sam)) {
			setKey(sam.getReadGroup().getSample(), -1, sam.getReadName().hashCode(), sam.getReadName().hashCode());
			context.write(keyout, outputValue);
			return;
		}

		String chrName = sam.getReferenceName();

		int[] winNums = getExtendPosition(sam.getAlignmentStart(), sam.getAlignmentEnd(),
				header.getSequence(chrName).getSequenceLength());
		for (int i = 0; i < 3; i++) {
			if (i != 0 && winNums[i] == winNums[0]) {
				continue;
			}
			setKey(sam, winNums[i]);
			context.write(keyout, outputValue);
		}
	}

	@Override
	protected void cleanup(Context context) throws IOException, InterruptedException {
		sampleIDs.clear();
	}
}

package org.bgi.flexlab.gaea.data.mapreduce.input.sam;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.bgi.flexlab.gaea.data.mapreduce.writable.SamRecordWritable;
import org.seqdoop.hadoop_bam.BAMRecordReader;
import org.seqdoop.hadoop_bam.util.SAMHeaderReader;

import hbparquet.hadoop.util.ContextUtil;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileReader;
import htsjdk.samtools.SAMFormatException;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordIterator;
import htsjdk.samtools.SAMTextHeaderCodec;
import htsjdk.samtools.ValidationStringency;

public class GaeaSamRecordReader extends RecordReader<LongWritable,SamRecordWritable>
{
	private LongWritable key = new LongWritable();
	private SamRecordWritable record = new SamRecordWritable();

	private FSDataInputStream input;
	private SAMRecordIterator iterator;
	private long start, end;
	private boolean isInitialized = false;

   private WorkaroundingStream waInput;

	@Override public void initialize(InputSplit spl, TaskAttemptContext ctx)
		throws IOException
	{
		if(isInitialized)
			close();
		isInitialized = true;

		final FileSplit split = (FileSplit)spl;

		this.start =         split.getStart();
		this.end   = start + split.getLength();

		final Configuration conf = ContextUtil.getConfiguration(ctx);

		final ValidationStringency stringency =
			SAMHeaderReader.getValidationStringency(conf);

		final Path file = split.getPath();
		final FileSystem fs = file.getFileSystem(conf);

		input = fs.open(file);

		ValidationStringency origStringency = null;
		try {
			if (stringency != null) {
				origStringency = SAMFileReader.getDefaultValidationStringency();
				SAMFileReader.setDefaultValidationStringency(stringency);
			}
			final SAMFileHeader header =
				new SAMFileReader(input, false).getFileHeader();

			waInput = new WorkaroundingStream(input, header);

			final boolean firstSplit = this.start == 0;

			if (firstSplit) {
				// Skip the header because we already have it, and adjust the start
				// to match.
				final int headerLength = waInput.getRemainingHeaderLength();
				input.seek(headerLength);
				this.start += headerLength;
			} else
				input.seek(--this.start);

			// Creating the iterator causes reading from the stream, so make sure
			// to start counting this early.
			waInput.setLength(this.end - this.start);

			iterator = new SAMFileReader(waInput, false).iterator();

			if (!firstSplit) {
				// Skip the first line, it'll be handled with the previous split.
				try {
					if (iterator.hasNext())
						iterator.next();
				} catch (SAMFormatException e) {}
			}
		} finally {
			if (origStringency != null)
				SAMFileReader.setDefaultValidationStringency(origStringency);
		}
	}
	@Override public void close() throws IOException { iterator.close(); }

	@Override public float getProgress() throws IOException {
		final long pos = input.getPos();
		if (pos >= end)
			return 1;
		else
			return (float)(pos - start) / (end - start);
	}
	@Override public LongWritable      getCurrentKey  () { return key; }
	@Override public SamRecordWritable getCurrentValue() { return record; }

	@Override public boolean nextKeyValue() {
		if (!iterator.hasNext())
			return false;

		final SAMRecord r = iterator.next();
		key.set(BAMRecordReader.getKey(r));
		record.set(r);
		return true;
	}
}

// See the long comment in SAMRecordReader.initialize() for what this does.
class WorkaroundingStream extends InputStream {
	private final InputStream stream, headerStream;
	private boolean headerRemaining;
	private long length;
	private int headerLength;

	private boolean lookingForEOL = false,
	                foundEOL      = false,
	                strippingAts  = false; // HACK, see read(byte[], int, int).

	public WorkaroundingStream(InputStream stream, SAMFileHeader header) {
		this.stream = stream;

		String text = header.getTextHeader();
		if (text == null) {
			StringWriter writer = new StringWriter();
			new SAMTextHeaderCodec().encode(writer, header);
			text = writer.toString();
		}
		byte[] b;
		try {
			b = text.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			b = null;
			assert false;
		}
		headerRemaining = true;
		headerLength    = b.length;
		headerStream    = new ByteArrayInputStream(b);

		this.length = Long.MAX_VALUE;
	}

	public void setLength(long length) {
		this.length = length;
	}

	public int getRemainingHeaderLength() {
		return headerLength;
	}

	private byte[] readBuf = new byte[1];
	@Override public int read() throws IOException {
		for (;;) switch (read(readBuf)) {
			case  0: continue;
			case  1: return readBuf[0];
			case -1: return -1;
		}
	}

	@Override public int read(byte[] buf, int off, int len) throws IOException {
		if (!headerRemaining)
			return streamRead(buf, off, len);

		int h;
		if (strippingAts)
			h = 0;
		else {
			h = headerStream.read(buf, off, len);
			if (h == -1) {
				// This should only happen when there was no header at all, in
				// which case Picard doesn't throw an error until trying to read
				// a record, for some reason. (Perhaps an oversight.) Thus we
				// need to handle that case here.
				assert (headerLength == 0);
				h = 0;
			} else if (h < headerLength) {
				headerLength -= h;
				return h;
			}
			strippingAts = true;
			headerStream.close();
		}

		final int newOff = off + h;
		int s = streamRead(buf, newOff, len - h);

		if (s <= 0)
			return strippingAts ? s : h;

		int i = newOff-1;
		while (buf[++i] == '@' && --s > 0);

		if (i != newOff)
			System.arraycopy(buf, i, buf, newOff, s);

		headerRemaining = s == 0;
		return h + s;
	}
	private int streamRead(byte[] buf, int off, int len) throws IOException {
		if (len > length) {
			if (foundEOL)
				return 0;
			lookingForEOL = true;
		}
		int n = stream.read(buf, off, len);
		if (n > 0) {
			n = tryFindEOL(buf, off, n);
			length -= n;
		}
		return n;
	}
	private int tryFindEOL(byte[] buf, int off, int len) {
		assert !foundEOL;

		if (!lookingForEOL || len < length)
			return len;

		// Find the first EOL between length and len.

		// len >= length so length fits in an int.
		int i = Math.max(0, (int)length - 1);

		for (; i < len; ++i) {
			if (buf[off + i] == '\n') {
				foundEOL = true;
				return i + 1;
			}
		}
		return len;
	}

	@Override public void close() throws IOException {
		stream.close();
	}

	@Override public int available() throws IOException {
		return headerRemaining ? headerStream.available() : stream.available();
	}
}


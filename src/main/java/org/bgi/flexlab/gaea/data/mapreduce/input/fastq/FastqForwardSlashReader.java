package org.bgi.flexlab.gaea.data.mapreduce.input.fastq;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

public class FastqForwardSlashReader extends FastqBasicReader{

	public FastqForwardSlashReader(Configuration job, FileSplit split,
			byte[] recordDelimiter) throws IOException {
		super(job, split, recordDelimiter);
	}

	@Override
	public boolean next(Text key, Text value) throws IOException {
		if (key == null) {
			key = new Text();
		}
		if (value == null) {
			value = new Text();
		}
		int newSize = 0;
		boolean iswrongFq = false;
		while (pos < end) {
			Text tmp = new Text();
			String[] st = new String[4];
			int startIndex = 0;

			if (firstLine != "") {
				st[0] = firstLine;
				st[1] = secondLine;
				startIndex = 2;
				firstLine = "";
				secondLine = "";
			}

			for (int i = startIndex; i < 4; i++) {
				newSize = in.readLine(tmp, maxLineLength, Math.max(
						(int) Math.min(Integer.MAX_VALUE, end - pos),
						maxLineLength));

				if (newSize == 0) {
					iswrongFq = true;
					break;
				}
				pos += newSize;
				st[i] = tmp.toString();
			}
			if (!iswrongFq) {
				int index = st[0].lastIndexOf("/");
				if (index < 0) {
					throw new RuntimeException("error read name format at reads:"
								+ st[0]);
				}
				String tempkey = st[0].substring(1, index).trim();
				String keyIndex = st[0].substring(index + 1);

				if (sampleID == null || sampleID.equals("") || sampleID.equals("+")) {
					key.set(">" + st[2]);
					value.set(tempkey + "\t" + keyIndex + "\t" + st[1] + "\t"
							+ st[3]);
				} else {
					key.set(">" + sampleID);
					value.set(tempkey + "\t" + keyIndex + "\t" + st[1] + "\t"
							+ st[3]);
				}
			} else {
				LOG.warn("wrong fastq reads:blank line among fq file or end of file!");
			}
			break;
		}
		if (newSize == 0 || iswrongFq) {
			key = null;
			value = null;
			return false;
		} else {
			return true;
		}
	}

}
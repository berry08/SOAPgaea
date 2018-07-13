/*******************************************************************************
 * Copyright (c) 2017, BGI-Shenzhen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *******************************************************************************/
package org.bgi.flexlab.gaea.framework.tools.mapreduce;

import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceRecord;
import org.bgi.flexlab.gaea.data.mapreduce.writable.SamRecordWritable;

import java.io.IOException;
import java.util.List;

public class WindowsBasedPlaceholderSamRecordMapper extends WindowsBasedMapper<SamRecordWritable>{

    @Override
    void otherSetup(Context context) throws IOException, InterruptedException {
        SAMRecord samRecord = new SAMRecord(header);
        samRecord.setReadName("placeholder");
        samRecord.setFlags(12);
        samRecord.setReferenceName("*");
        samRecord.setAlignmentStart(0);
        samRecord.setMappingQuality(0);
        samRecord.setCigarString("*");
        samRecord.setMateReferenceName("*");
        samRecord.setMateAlignmentStart(0);
        samRecord.setInferredInsertSize(0);
        samRecord.setReadString("*");
        samRecord.setBaseQualityString("*");

        List<SAMSequenceRecord> sequences = header.getSequenceDictionary().getSequences();
        for (SAMSequenceRecord seqRec : sequences) {
            int index = seqRec.getSequenceIndex();
            outputValue.set(samRecord);
            int winNum = (int) Math.ceil(seqRec.getSequenceLength() / windowsSize);
            for (int i = 0; i < winNum; i++) {
                int start = i * windowsSize + 1;
                for (String sample: sampleIDs.keySet()) {
                    setKey(sample, index, i, start);
                    context.write(keyout, outputValue);
                }
            }
        }
    }

    @Override
    void setOutputValue(SAMRecord samRecord) {
        outputValue.set(samRecord);
    }

    @Override
    void initOutputVaule() {
        outputValue = new SamRecordWritable();
    }
}
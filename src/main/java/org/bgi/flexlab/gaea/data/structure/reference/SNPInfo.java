/**
 * Copyright (c) 2011, BGI and/or its affiliates. All rights reserved.
 * BGI PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package org.bgi.flexlab.gaea.data.structure.reference;

import java.io.Serializable;

/** 
 * dbSNP数据库信息类（针对单个位点）
 * @author ZhangZhi
 *
 */
public class SNPInfo implements Serializable{

	/**
	 * 序列化版本ID
	 */
	private static final long serialVersionUID = -2733424886798417676L;
	
	/**
	 * 等位基因频率
	 */
	private float alleleFreq;
	
	/**
	 * SNP基本信息
	 * 从低位开始存储
	 * 第1位表示是否是验证过的SNP信息(1：是；0：否）
	 * 第2位表示是否是有等位基因频率信息(1：是；0：否）
	 * 第3-6位分别表示GTCA是否为等位基因(1：是；0：否）
	 */
	private byte snpBasicInfo;

	/**
	 * 设置snpBasicInfo属性
	 * @param snpBasicInfo
	 */
	public void setSnpBasicInfo(byte snpBasicInfo) {
		this.snpBasicInfo = snpBasicInfo;
	}

	/**
	 * 判断是否是验证过的SNP信息
	 * @return boolean
	 */
	public boolean isValidated() {
		return (snpBasicInfo & 0x1) == 1;
	}

	/**
	 * 判断该SNP对应位点是否有等位基因频率信息(如果没有等位基因频率信息,则可以将等位基因频率赋值为任意正数)
	 * @return boolean
	 */
	public boolean isHapMap() {
		return (snpBasicInfo & 0x2) == 2;
	}

	/**
	 * 获取某个碱基的频率
	 * @param allele: 0:A, 1:C, 2:T, 3:G
	 * @return
	 */
	public float getAlleleFreq(byte allele) {		
		// 等位基因频率状态, 0:等位基因频率为0; 1:等位基因频率不为零
		boolean freqAStatus = !((snpBasicInfo & 32) == 0);
		boolean freqCStatus = !((snpBasicInfo & 16) == 0);
		boolean freqTStatus = !((snpBasicInfo & 8) == 0);
		boolean freqGStatus = !((snpBasicInfo & 4) == 0);
		//System.out.println(freqAStatus + "\t" + freqCStatus + "\t" + freqTStatus + "\t" + freqGStatus);
		float freq = 0f;
		switch (allele) {
		case 0: // A
			if (freqAStatus) {
				freq = alleleFreq;
			}
			break;
		case 1: // C
			if (freqCStatus && !freqAStatus) {
				freq = alleleFreq;
			} else if (freqCStatus && freqAStatus) {
				freq = 1 - alleleFreq;
			}
			break;
		case 2: // T
			if (freqTStatus && !freqAStatus && !freqCStatus) {
				freq = alleleFreq;
			} else if (freqTStatus && (freqAStatus || freqCStatus)) {
				freq = 1 - alleleFreq;
			}
			break;
		case 3: // G
			if (freqGStatus) {
				freq = 1 - alleleFreq;
			}
			break;
		}
		return freq;
	}	

	/**
	 * 设置碱基频率
	 * @param alleleFreq
	 */
	public void setAlleleFreq(float alleleFreq) {
		this.alleleFreq = alleleFreq;
	}
	
}

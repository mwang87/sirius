/*
 *  This file is part of the SIRIUS library for analyzing MS and MS/MS data
 *
 *  Copyright (C) 2013-2015 Kai Dührkop
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with SIRIUS.  If not, see <http://www.gnu.org/licenses/>.
 */
package fragtreealigner.util;

import fragtreealigner.algorithm.ScoringFunctionNeutralLosses;
import fragtreealigner.algorithm.ScoringFunctionSimple;
import fragtreealigner.algorithm.TreeAligner;
import fragtreealigner.domainobjects.Alignment;
import fragtreealigner.domainobjects.graphs.AlignmentTree;
import fragtreealigner.domainobjects.graphs.FragmentationTree;

import java.io.*;

@SuppressWarnings("serial")
public class Macros implements Serializable {
	
	public static Alignment performSimpleAlignment(String filename1, String filename2, Session session) {
		ScoringFunctionSimple sFunc = new ScoringFunctionSimple(session);
		AlignmentTree aTree1 = new AlignmentTree();
		AlignmentTree aTree2 = new AlignmentTree();
		try {
			aTree1.readFromList(new BufferedReader(new FileReader(filename1)));
			aTree2.readFromList(new BufferedReader(new FileReader(filename2)));
		} catch (Exception e) {
			System.err.println("The following error occured while reading the files:\n" + e.getMessage());
		}
		TreeAligner treeAligner = new TreeAligner(aTree1, aTree2, sFunc, session);
		Alignment alig = treeAligner.performAlignment();
		return alig;
	}

	public static Alignment performFragTreeAlignment(String filename1, String filename2, Session session) {
		ScoringFunctionNeutralLosses sFuncNL = new ScoringFunctionNeutralLosses(session);
		FragmentationTree fTree1 = null, fTree2 = null;	
		try {
			if (filename1.endsWith(".dot")) fTree1 = FragmentationTree.readFromDot(new BufferedReader(new FileReader(filename1)), session);
			else if (filename1.endsWith(".cml")) fTree1 = FragmentationTree.readFromCml(new BufferedReader(new FileReader(filename1)), session);
			if (filename2.endsWith(".dot")) fTree2 = FragmentationTree.readFromDot(new BufferedReader(new FileReader(filename2)), session);
			else if (filename2.endsWith(".cml")) fTree2 = FragmentationTree.readFromCml(new BufferedReader(new FileReader(filename2)), session);
		} catch (Exception e) {
			System.err.println("The following error occured while reading the files:\n" + e.getMessage());
		}
		if (fTree1 == null || fTree2 == null){
			System.err.println("A tree was empty.");
			return null;
		}
		AlignmentTree aTree1 = fTree1.toAlignmentTree();
		AlignmentTree aTree2 = fTree2.toAlignmentTree();

		TreeAligner treeAligner = new TreeAligner(aTree1, aTree2, sFuncNL, session);
		Alignment alig = treeAligner.performAlignment();
		return alig;
	}

	
	public static String readFileToString(String filename) {
		StringBuffer strBuffer = new StringBuffer();
		String line;

		try {
			BufferedReader br = new BufferedReader(new FileReader(filename));			
			while((line = br.readLine()) != null) {
				strBuffer.append(line + "\n");
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return new String(strBuffer); 
	}

}

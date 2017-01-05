package com.odianyun.util.sensi;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

public class SensitiveFilterTest extends TestCase{
	
	public void test() throws Exception{
		
		SensitiveFilter filter = SensitiveFilter.DEFAULT;
		
		System.out.println(filter.filter("会上，主席进行了发言。", '*'));
		
	}
	
	public void testSpeed() throws Exception{
		
		PrintStream ps = new PrintStream("/data/敏感词替换结果.txt");
		
		File dir = new File("/data/穿越小说2011-10-14");
		
		List<String> testSuit = new ArrayList<String>(1048576);
		long length = 0;
		
		for(File file: dir.listFiles()){
			if(file.isFile() && file.getName().endsWith(".txt")){
				BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "gb18030"));
				for(String line = br.readLine(); line != null; line = br.readLine()){
					if(line.trim().length() > 0){
						testSuit.add(line);
						length += line.length();
					}
				}
				br.close();
			}
		}
		
		System.out.println(String.format("共加载 %d 行，%d 字符。", testSuit.size(), length));
		
		
		SensitiveFilter filter = SensitiveFilter.DEFAULT;
		
		int replaced = 0;
		
		for(String line: testSuit){
			if(! line.contains("`")){
				String result = filter.filter(line, '`');
				if(result.contains("`")){
					ps.println(line);
					ps.println(result);
					ps.println();
					replaced ++;
				}
			}
		}
		ps.close();
		
		long timer = System.currentTimeMillis();
		for(String line: testSuit){
			filter.filter(line, '*');
		}
		timer = System.currentTimeMillis() - timer;
		System.out.println(String.format("共耗时 %1.3f 秒， 速度为 %1.1f字符/毫秒", timer * 1E-3, length / (double) timer));
		System.out.println(String.format("其中 %d 行有替换", replaced));
		
	}

}

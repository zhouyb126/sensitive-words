# 高效敏感词过滤


## 性能概述

在共60M穿越小说上测试，单核性能为80M字符每秒（i7 2.3GHz）。
相比类似原理的正向最大匹配分词，性能一般在1M字节每秒左右有很大提升，类似的优化方式可以用在分词器上。

```
敏感词 14580 条
共加载 599254 行，30613005 字符。
共耗时 0.381 秒， 速度为 80349.1字符/毫秒
```

## 优化方式

主要的优化目标是速度，从以下方面优化：

1. 敏感词都是2个字以上的，
2. 对于句子中的一个位置，用2个字符的hash在稀疏的hash桶中查找，如果查不到说明一定不是敏感词，则继续下一个位置。
3. 2个字符（2x16位），可以预先组合为1个int（32位）的mix，即使hash命中，如果mix不同则跳过。
4. StringPointer，在不生成新实例的情况下计算任意位置2个字符的hash和mix
5. StringPointer，尽量减少实例生成和char数组的拷贝。

## 敏感词库

自带敏感词库拷贝自 https://github.com/observerss/textfilter ，并删除如`女人`、`然后`这样的几个常用词。
如果需要自带敏感词的实例，可以直接使用下面的方式：


```java
// 使用默认的单例（即加载了自带敏感词库的）
SensitiveFilter filter = SensitiveFilter.DEFAULT;
// 对一个句子过滤
System.out.println(filter.filter("会上，主席进行了发言。", '*'));
```

打印结果

```
会上，**进行了发言。
```

## 代码只有3个类直接贴上


### SensitiveFilter.java
```java
package com.odianyun.util.sensi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;

/**
 * 敏感词过滤器，以过滤速度优化为主。<br/>
 * * 增加一个敏感词：{@link #put(String)} <br/>
 * * 过滤一个句子：{@link #filter(String, char)} <br/>
 * * 获取默认的单例：{@link #DEFAULT}
 * 
 * @author ZhangXiaoye
 * @date 2017年1月5日 下午4:18:38
 */
public class SensitiveFilter implements Serializable{
	
	private static final long serialVersionUID = 1L;

	/**
	 * 默认的单例，使用自带的敏感词库
	 */
	public static final SensitiveFilter DEFAULT = new SensitiveFilter(
			new BufferedReader(new InputStreamReader(
					ClassLoader.getSystemResourceAsStream("sensi_words.txt")
					, StandardCharsets.UTF_8)));
	
	/**
	 * 为2的n次方，考虑到敏感词大概在10k左右，
	 * 这个数量应为词数的数倍，使得桶很稀疏
	 * 提高不命中时hash指向null的概率，
	 * 加快访问速度。
	 */
	static final int DEFAULT_INITIAL_CAPACITY = 131072;
	
	/**
	 * 类似HashMap的桶，比较稀疏。
	 * 使用2个字符的hash定位。
	 */
	protected SensitiveNode[] nodes = new SensitiveNode[DEFAULT_INITIAL_CAPACITY];
	
	/**
	 * 构建一个空的filter
	 * 
	 * @author ZhangXiaoye
	 * @date 2017年1月5日 下午4:18:07
	 */
	public SensitiveFilter(){
		
	}
	
	/**
	 * 加载一个文件中的词典，并构建filter<br/>
	 * 文件中，每行一个敏感词条<br/>
	 * <b>注意：</b>读取完成后会调用{@link BufferedReader#close()}方法。<br/>
	 * <b>注意：</b>读取中的{@link IOException}不会抛出
	 * 
	 * @param reader 
	 * @author ZhangXiaoye
	 * @date 2017年1月5日 下午4:21:06
	 */
	public SensitiveFilter(BufferedReader reader){
		try{
			for(String line = reader.readLine(); line != null; line = reader.readLine()){
				put(line);
			}
			reader.close();
		}catch(IOException e){
			e.printStackTrace();
		}
	}
	
	/**
	 * 增加一个敏感词，如果词的长度（trim后）小于2，则丢弃<br/>
	 * 此方法（构建）并不是主要的性能优化点。
	 * 
	 * @param word
	 * @author ZhangXiaoye
	 * @date 2017年1月5日 下午2:35:21
	 */
	public void put(String word){
		if(word == null || word.trim().length() < 2){
			return;
		}
		StringPointer sp = new StringPointer(word.trim());
		// 计算头两个字符的hash
		int hash = sp.nextTwoCharHash(0);
		// 计算头两个字符的mix表示（mix相同，两个字符相同）
		int mix = sp.nextTwoCharMix(0);
		// 转为在hash桶中的位置
		int index = hash & (nodes.length - 1);
		
		// 从桶里拿第一个节点
		SensitiveNode node = nodes[index];
		if(node == null){
			// 如果没有节点，则放进去一个
			node = new SensitiveNode(mix);
			// 并添加词
			node.words.add(sp);
			// 放入桶里
			nodes[index] = node;
		}else{
			// 如果已经有节点（1个或多个），找到正确的节点
			for(;node != null; node = node.next){
				// 匹配节点
				if(node.headTwoCharMix == mix){
					node.words.add(sp);
					return;
				}
				// 如果匹配到最后仍然不成功，则追加一个节点
				if(node.next == null){
					new SensitiveNode(mix, node).words.add(sp);
					return;
				}
			}
		}
	}
	
	/**
	 * 对句子进行敏感词过滤
	 * 
	 * @param sentence 句子
	 * @param replace 敏感词的替换字符
	 * @return 过滤后的句子
	 * @author ZhangXiaoye
	 * @date 2017年1月5日 下午4:16:31
	 */
	public String filter(String sentence, char replace){
		// 先转换为StringPointer
		StringPointer sp = new StringPointer(sentence);
		
		// 标示是否替换
		boolean replaced = false;
		
		// 匹配的起始位置
		int i = 0;
		while(i < sp.length - 2){
			/*
			 * 移动到下一个匹配位置的步进：
			 * 如果未匹配为1，如果匹配是匹配的词长度
			 */
			int step = 1;
			// 计算此位置开始2个字符的hash
			int hash = sp.nextTwoCharHash(i);
			/*
			 * 根据hash获取第一个节点，
			 * 真正匹配的节点可能不是第一个，
			 * 所以有后面的for循环。
			 */
			SensitiveNode node = nodes[hash & (nodes.length - 1)];
			/*
			 * 如果非敏感词，node基本为null。
			 * 这一步大幅提升效率 
			 */
			if(node != null){
				/*
				 * 如果能拿到第一个节点，
				 * 才计算mix（mix相同表示2个字符相同）。
				 * mix的意义和HashMap先hash再equals的equals部分类似。
				 */
				int mix = sp.nextTwoCharMix(i);
				/*
				 * 循环所有的节点，如果非敏感词，
				 * mix相同的概率非常低，提高效率
				 */
				for(; node != null; node = node.next){
					/*
					 * 对于一个节点，先根据头2个字符判断是否属于这个节点。
					 * 如果属于这个节点，看这个节点的词库是否命中。
					 * 此代码块中访问次数已经很少，不是优化重点
					 */
					if(node.headTwoCharMix == mix){
						/*
						 * 查出比剩余sentence小的最大的词。
						 * 例如剩余sentence为"色情电影哪家强？"，
						 * 这个节点含三个词从小到大为：“色情”、“色情电影”、“色情信息”。
						 * 则取到的word为“色情电影”
						 */
						StringPointer word = node.words.floor(sp.substring(i));
						/*
						 * 仍然需要再判断一次，例如“色情信息哪里有？”，
						 * 如果节点只包含“色情电影”一个词，
						 * 仍然能够取到word为“色情电影”，但是不该匹配。
						 */
						if(word != null && sp.nextStartsWith(i, word)){
							// 匹配成功，将匹配的部分，用replace制定的内容替代
							sp.fill(i, i + word.length, replace);
							// 跳过已经替代的部分
							step = word.length;
							// 标示有替换
							replaced = true;
							// 跳出for循环（然后是while循环的下一个位置）
							break;
						}
					}
				}
			}
			
			// 移动到下一个匹配位置
			i += step;
		}
		
		// 如果没有替换，直接返回入参（节约String的构造copy）
		if(replaced){
			return sp.toString();
		}else{
			return sentence;
		}
	}

}
```

### SensitiveNode.java


```java
package com.odianyun.util.sensi;

import java.io.Serializable;
import java.util.TreeSet;

/**
 * 敏感词节点，每个节点包含了以相同的2个字符开头的所有词
 * 
 * @author ZhangXiaoye
 * @date 2017年1月5日 下午5:06:26
 */
public class SensitiveNode implements Serializable{
	
	private static final long serialVersionUID = 1L;

	/**
	 * 头两个字符的mix，mix相同，两个字符相同
	 */
	protected final int headTwoCharMix;
	
	/**
	 * 所有以这两个字符开头的词表
	 */
	protected final TreeSet<StringPointer> words = new TreeSet<StringPointer>();
	
	/**
	 * 下一个节点
	 */
	protected SensitiveNode next;
	
	public SensitiveNode(int headTwoCharMix){
		this.headTwoCharMix = headTwoCharMix;
	}
	
	public SensitiveNode(int headTwoCharMix, SensitiveNode parent){
		this.headTwoCharMix = headTwoCharMix;
		parent.next = this;
	}

}
```

### StringPointer.java

```java
package com.odianyun.util.sensi;

import java.io.Serializable;
import java.util.HashMap;
import java.util.TreeMap;

/**
 * 没有注释的方法与{@link String}类似<br/>
 * <b>注意：</b>没有（数组越界等的）安全检查<br/>
 * 可以作为{@link HashMap}和{@link TreeMap}的key
 * 
 * @author ZhangXiaoye
 * @date 2017年1月5日 下午2:11:56
 */
public class StringPointer implements Serializable, CharSequence, Comparable<StringPointer>{
	
	private static final long serialVersionUID = 1L;

	protected final char[] value;
	
	protected final int offset;
	
	protected final int length;
	
	private int hash = 0;
	
	public StringPointer(String str){
		value = str.toCharArray();
		offset = 0;
		length = value.length;
	}
	
	public StringPointer(char[] value, int offset, int length){
		this.value = value;
		this.offset = offset;
		this.length = length;
	}
	
	/**
	 * 计算该位置后（包含）2个字符的hash值
	 * 
	 * @param i 从 0 到 length - 2
	 * @return hash值
	 * @author ZhangXiaoye
	 * @date 2017年1月5日 下午2:23:02
	 */
	public int nextTwoCharHash(int i){
		return 31 * value[offset + i] + value[offset + i + 1];
	}
	
	/**
	 * 计算该位置后（包含）2个字符和为1个int型的值<br/>
	 * int值相同表示2个字符相同
	 * 
	 * @param i 从 0 到 length - 2
	 * @return int值
	 * @author ZhangXiaoye
	 * @date 2017年1月5日 下午2:46:58
	 */
	public int nextTwoCharMix(int i){
		return (value[offset + i] << 16) | value[offset + i + 1];
	}
	
	/**
	 * 该位置后（包含）的字符串，是否以某个词（word）开头
	 * 
	 * @param i 从 0 到 length - 2
	 * @param word 词
	 * @return 是否？
	 * @author ZhangXiaoye
	 * @date 2017年1月5日 下午3:13:49
	 */
	public boolean nextStartsWith(int i, StringPointer word){
		// 是否长度超出
		if(word.length > length - i){
			return false;
		}
		// 从尾开始判断
		for(int c =  word.length - 1; c >= 0; c --){
			if(value[offset + i + c] != word.value[word.offset + c]){
				return false;
			}
		}
		return true;
	}
	
	/**
	 * 填充（替换）
	 * 
	 * @param begin 从此位置开始（含）
	 * @param end 到此位置结束（不含）
	 * @param fillWith 以此字符填充（替换）
	 * @author ZhangXiaoye
	 * @date 2017年1月5日 下午3:29:21
	 */
	public void fill(int begin, int end, char fillWith){
		for(int i = begin; i < end; i ++){
			value[offset + i] = fillWith;
		}
	}
	
	public int length(){
		return length;
	}
	
	public char charAt(int i){
		return value[offset + i];
	}
	
	public StringPointer substring(int begin){
		return new StringPointer(value, offset + begin, length - begin);
	}
	
	public StringPointer substring(int begin, int end){
		return new StringPointer(value, offset + begin, end - begin);
	}

	@Override
	public CharSequence subSequence(int start, int end) {
		return substring(start, end);
	}
	
	public String toString(){
		return new String(value, offset, length);
	}
	
	public int hashCode() {
		int h = hash;
		if (h == 0 && length > 0) {
			for (int i = 0; i < length; i++) {
				h = 31 * h + value[offset + i];
			}
			hash = h;
		}
		return h;
	}
	
	public boolean equals(Object anObject) {
        if (this == anObject) {
            return true;
        }
        if (anObject instanceof StringPointer) {
        	StringPointer that = (StringPointer)anObject;
            if (length == that.length) {
                char v1[] = this.value;
                char v2[] = that.value;
                for(int i = 0; i < this.length; i ++){
                	if(v1[this.offset + i] != v2[that.offset + i]){
                		return false;
                	}
                }
                return true;
            }
        }
        return false;
    }

	@Override
	public int compareTo(StringPointer that) {
		int len1 = this.length;
        int len2 = that.length;
        int lim = Math.min(len1, len2);
        char v1[] = this.value;
        char v2[] = that.value;

        int k = 0;
        while (k < lim) {
            char c1 = v1[this.offset + k];
            char c2 = v2[that.offset + k];
            if (c1 != c2) {
                return c1 - c2;
            }
            k++;
        }
        return len1 - len2;
	}

}
```


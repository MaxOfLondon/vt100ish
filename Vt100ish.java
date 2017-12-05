/****************************************************
Nortel DMS switch console emulator v1.6

This utility interprets subset of VT100, ANSI- and ISO
terminal control sequences then emulates console with 
colour coding and anchor points removed. It will return
string with text, whitespaces and new line characters only
allowing it to be post processed by SST for web display.

v1.0 - 21/04/2017 Max
v1.1 - 27/04/2017 Max, added handling of cursor_down (but not cursor_left, _right or _up 
as these do not seem to appear in any test data.
v1.2 - 28/04/2017 Max, added handling of cursor save, cursor restore, erase in line
handled malformed move to command where on third char of argument a random
character appear i.e. sequence ESC[00*,000H where * is one of ['-/] (ascii 39 - 47)
Changed example main to take filename as an input. Fix was to + 9 to shift value to number 
range.
v1.3 - 28/05/2017 Max. Reverted fix v1.2 as per VT100 specification malformed commands
are ignored. 
Reworked all P_ regex.
Added missing break statement in render case ERASE_IN_LINE.
Rewritten form2Tokens.
Other fixes.
v1.4 - 18/08/2017 Max. changed patterns P_MOVE_CURSOR and PS_MOVE_CURSOR to
accomodate Lisbon switch not padding values with 0 to string length 3.
Handled DATA == null in few places.
v1.5 - 23/08/2017 Max. some improvements to handle Lisbon. Changed return value for getData() from null
v1.6 - 05/09/2017 Max: added DUMP_MODE that saves input of parse() converted to byte[] in /tmp for debugging

****************************************************/
package com.maxoflondon.ossutils.vt100ish;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.TimeZone;
import java.util.Date;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.text.DateFormat;

public class Vt100ish {

	public static final boolean DEV_MODE = false;
	public static final long PRINT_DELAY = 100;	
	public static final boolean DUMP_MODE = true;
	public static final int DUMP_LEVEL = 3;
	
	//VT100 command refs: 
	// http://vt100.net/docs/vt102-ug/chapter5.html
	// http://ascii-table.com/ansi-escape-sequences-vt-100.php
	// https://www.gnu.org/software/screen/manual/html_node/Control-Sequences.html
	public static enum COMMAND {
		CURSOR_HOME {
			// ESC[H
			@Override
			public String toString() {
				return "CURSOR_HOME";
			}
		},
		ERASE_IN_DISPLAY {
			// ESC[J
			// ESC [ Pn J
			// Pn = None or 0            From Cursor to End of Screen
			// 1                    From Beginning of Screen to Cursor
			// 2                    Entire Screen
			@Override
			public String toString() {
				return "ERASE_IN_DISPLAY";
			}
		},
		ERASE_IN_LINE {
			/*
			ESC [ Pn K                      Erase in Line
			Pn = None or 0            From Cursor to End of Line
                1                    From Beginning of Line to Cursor
                2                    Entire Line
			*/
			@Override 
			public String toString() {
				return "ERASE_IN_LINE";
			}
		},
		SELECT_GRAPHIC_RENDITION {
			/*
			ESC [ Ps ;...; Ps m             Select Graphic Rendition
			Ps = None or 0            Default Rendition
			*/
			@Override
			public String toString() {
				return "SELECT_GRAPHIC_RENDITION";
			}
		},
		SAVE_CURSOR_ATTRIBS {
			// <ESC>7
			// ESC [s
			// saves cursor attributes and position
			@Override
			public String toString() {
				return "SAVE_CURSOR_ATTRIBS";
			}
		},
		RESTORE_CURSOR_ATTRIBS {
			// <ESC>8
			// ESC [u
			@Override
			public String toString() {
				return "RESTORE_CURSOR_ATTRIBS";
			}
		},
		MOVE_CURSOR {
			// <ESC>[Pn;PnH
			@Override
			public String toString() {
				return "MOVE_CURSOR";
			}
		},
		CURSOR_UP {
			//ESC [ Pn A                      Cursor Up
			@Override
			public String toString() {
				return "CURSOR_UP";
			}
		},
		CURSOR_DOWN {
			// ESC [ Pn B                      Cursor Down
			@Override
			public String toString() {
				return "CURSOR_DOWN";
			}
		},		
		CURSOR_RIGHT {
			// ESC [ Pn C                      Cursor Right
			@Override
			public String toString() {
				return "CURSOR_RIGHT";
			}
		},				
		CURSOR_LEFT {
			// ESC [ Pn D                      Cursor Left
			@Override
			public String toString() {
				return "CURSOR_LEFT";
			}
		},			
		DATA {
			// whatever is not classified or ignored will be data for display
			@Override
			public String toString() {
				return "DATA";
			}
		},
		IGNORE {
			// 
			@Override
			public String toString() {
				return "IGNORE";
			}
		}
	};
	
	public static COMMAND fromString(String text) {
		if (text != null) {
			for (COMMAND b : COMMAND.values()) {
				if (text.equalsIgnoreCase(b.name())) {
					return b;
				}
			}
			return COMMAND.IGNORE;
		}
		return null;
	}
	

	public static final Pattern P_CURSOR_HOME = Pattern.compile("^(\\x1b\\[H)(?:.*)$");
	//public static final Pattern P_MOVE_CURSOR = Pattern.compile("^(\\x1b\\[(0[0-9]{2});(0[0-9]{2})H)(?:.*)$");
	public static final Pattern P_MOVE_CURSOR = Pattern.compile("^(\\x1b\\[([0-9]+);([0-9]+)H)(?:.*)$");
	public static final Pattern P_CURSOR_UP = Pattern.compile("^(\\x1b\\[([0-9]+)A)(?:.*)$");
	public static final Pattern P_CURSOR_DOWN = Pattern.compile("^(\\x1b\\[([0-9]+)B)(?:.*)$");
	public static final Pattern P_CURSOR_RIGHT = Pattern.compile("^(\\x1b\\[([0-9]+)C)(?:.*)$");	
	public static final Pattern P_CURSOR_LEFT = Pattern.compile("^(\\x1b\\[([0-9]+)D)(?:.*)$");
	public static final Pattern P_SAVE_CURSOR_ATTRIBS = Pattern.compile("^(\\x1b7)(?:.*)$");
	public static final Pattern P_RESTORE_CURSOR_ATTRIBS = Pattern.compile("^(\\x1b8)(?:.*)$");
	public static final Pattern P_SELECT_GRAPHIC_RENDITION = Pattern.compile("^(\\x1b\\[(?:[0-9]*;*){0,3}m)(?:.*)$"); 
	public static final Pattern P_ERASE_IN_DISPLAY = Pattern.compile("^(\\x1b\\[[0-2]*J)(?:.*)$");
	public static final Pattern P_ERASE_IN_LINE = Pattern.compile("^(\\x1b\\[([0-2]*)K)(?:.*)$");
	public static final Pattern P_IGNORE = Pattern.compile("^\\x1b");

	public static final Pattern PS_CURSOR_HOME = Pattern.compile("^(\\x1b\\[H)(.+)$");
	//public static final Pattern PS_MOVE_CURSOR = Pattern.compile("^(\\x1b\\[0[0-9]{2};0[0-9]{2}H)(.+)$");
	public static final Pattern PS_MOVE_CURSOR = Pattern.compile("^(\\x1b\\[([0-9]+);([0-9]+)H)(.+)$");
	public static final Pattern PS_CURSOR_UP = Pattern.compile("^(\\x1b\\[[0-9]+A)(.+)$");
	public static final Pattern PS_CURSOR_DOWN = Pattern.compile("^(\\x1b\\[[0-9]+B)(.+)$");
	public static final Pattern PS_CURSOR_RIGHT = Pattern.compile("^(\\x1b\\[[0-9]+C)(.+)$");	
	public static final Pattern PS_CURSOR_LEFT = Pattern.compile("^(\\x1b\\[[0-9]+D)(.+)$");	
	public static final Pattern PS_SAVE_CURSOR_ATTRIBS = Pattern.compile("^(\\x1b7)(.+)$");
	public static final Pattern PS_RESTORE_CURSOR_ATTRIBS = Pattern.compile("^(\\x1b8)(.+)$");
	public static final Pattern PS_SELECT_GRAPHIC_RENDITION = Pattern.compile("^(\\x1b\\[(?:[0-9]*;*){0,3}m)(.+)$");
	public static final Pattern PS_ERASE_IN_DISPLAY = Pattern.compile("^(\\x1b\\[[0-2]*J)(.+)$");
	public static final Pattern PS_ERASE_IN_LINE = Pattern.compile("^(\\x1b\\[[0-2]*K)(.+)$");
	public static final Pattern PS_IGNORE = Pattern.compile("^(\\x1b)(.*)");	
	
	// private members
	private byte[] bytes;
	private List<Token> tokens;
	private Console console;
	private byte[] fifo = new byte[3];
	
	// private classes as I was too lazy to create separate files
	private class Console {
		private int rows;
		private int cols;
		private boolean wrap;
		private char[][] buffer; 
		private int savedCursorX;
		private int savedCursorY;		
		public int cursorX;
		public int cursorY;
		
		public Console(int cols, int rows, boolean wrapping) {
			this.rows = rows;
			this.cols = cols;
			this.wrap = wrapping;
			this.buffer = new char[cols][rows];
			clear();
		}
		
		public Console(){
			this(80, 24, false);
		}
		
		public void clear() {
			for(int i = 0; i < rows; i++) {
				for(int j = 0; j < cols; j++) {
					
					if (!Vt100ish.DEV_MODE) {
						buffer[j][i] = 0x20;
					} else {
						buffer[j][i] = 0x7e;
					}
				}
			}
			cursorX = 0;
			cursorY = 0;
		}
		
		public void write(int x, int y, char[] src) {
			cursorX = x;
			cursorY = y;		
			for (int i = 0; i < src.length ; i++) {
				if (cursorX == cols) break;
				buffer[cursorX++][cursorY] = src[i];
			}
			if (Vt100ish.DEV_MODE) {
				System.out.print(String.format("%c[%d;%df",0x1B,0,0));
				System.out.print(this.toString());
				try{
					Thread.sleep(Vt100ish.PRINT_DELAY);
				} catch (Exception e) { }
			}
		}
		
		public void write(char[] src) {
			write(cursorX, cursorY, src);
		}
		
		public void display() {
			System.out.print(this.toString());
		}
		
		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			
			for (int x = 0; x < rows; x++) {
				for (int y = 0; y < cols; y++) {
					sb.append(buffer[y][x]);
				}
				sb.append('\n');
			}
			return sb.toString();
		}
		
		public int getRowsCount() {
			return rows;
		}
		
		public int getColsCount() {
			return cols;
		}
		
		public boolean getWrapping() {
			return wrap;
		}
		
		public void saveCursorPos() {
			savedCursorX = cursorX;
			savedCursorY = cursorY;
		}
		
		public void restoreCursorPos() {
			cursorX = savedCursorX;
			cursorY = savedCursorY;
		}
	}
	
	private class Token {
		int start; // start offset
		int end; // end offset
		COMMAND type; // type of token
		
		public Token() { }
		
		public Token(Token t) {
			start = t.getStartOffset();
			end = t.getEndOffset();
			type = t.getType();
		}
		
		public int getStartOffset() {
			return start;
		}
		
		public void setStartOffset(int n) {
			start = n;
		}
		
		public int getEndOffset() {
			return end;
		}
		
		public void setEndOffset(int n) {
			end = n;
		}
		
		public COMMAND getType() {
			return type;
		}
		
		public void setType(COMMAND n) {
			type = n;
		}
		
		public int length() {
			return end - start + 1;
		}
		
		public byte[] getData() {
			if ((Vt100ish.this.bytes != null) && (start < end) && (end < Vt100ish.this.bytes.length)) {			
				return Arrays.copyOfRange(Vt100ish.this.bytes, start, Math.min(end+1, Vt100ish.this.bytes.length-1));
			}
			return new byte[] {0x00};
		}
		
		@Override
		public String toString(){
			return String.format("[%d, %d, %d, %s]", start, end, length(), type);
		}
	}
	

/*********************** PUBLIC METHODS ***********************/	
	/*
		The imput stream must have no CR or LF.
		It is assumed there is no white spaces in the commands.- if there are then 
		all command classifying regex will need to be reviewed
		Whitespaces are permitted in data and will be reflected acoordingly.
	*/
	public void parse(InputStream stream) throws IOException {
	
		// read stream into byte array
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		int nRead;
		byte[] data = new byte[1024];
		while ((nRead = stream.read(data, 0, data.length)) > -1) {
			buffer.write(data, 0, nRead);
		}
		buffer.write(new byte[] {0x00}, 0, 1);
		
		buffer.flush();

		bytes = new byte[buffer.size()];
		bytes = buffer.toByteArray();
		tokens = new LinkedList<Token>();
		if (console == null) {
			console = new Console();
		} else {
			console = new Console(console.getColsCount(), console.getRowsCount(), console.getWrapping());
		}
		
		if (DUMP_MODE && (DUMP_LEVEL > 2)) {
			try {
				FileOutputStream fos = new FileOutputStream(System.getProperty("java.io.tmpdir")+"/vt100ish-"+getDateTimeMillis()+".dump");
				fos.write(bytes);
				fos.close();
			} catch (Exception e) {
				System.err.println(e);
			}
		}
		
		// split into tokens on <ESC> (x01b) boundaries
		// there will be tokens that will be followed by data
		// yet be part of command
		for (int i = 0; i < bytes.length; i++) {
			byte b =  bytes[i];
			char c = (char) (b & 0xff);


			fifo[0] = fifo[1]; fifo[1] = fifo[2]; fifo[2] = b;
			if ((i == 0) || (c == 0x1b)) {
				Token t = new Token();
				t.setStartOffset(i);
/* 				if (b == 0) {
					bytes[i] = 0x20;
					t.setType(COMMAND.IGNORE);
				} */
				if (Arrays.equals(fifo, "[00".getBytes())
					&& (c  > 0x1f)
					&& (c < 0x30)) {
						//bytes[i] = (byte) (b + 0x09);
						//c  = (char) (bytes[i] & 0xff);
						t.setType(COMMAND.IGNORE);
				}
				tokens.add(t);
			}
		}
		
		// traverse from back setting end token indecies
		tokens.get(tokens.size() -1).setEndOffset(bytes.length -1);
		for(int i = tokens.size() -1; i > 0; i--) {
			tokens.get(i-1).setEndOffset(tokens.get(i).getStartOffset()-1);
			if (tokens.get(i).getType() != COMMAND.IGNORE) {
				if (tokens.get(i).getData() != null) {
					tokens.get(i).setType(classifyToken(new String(tokens.get(i).getData())));
				} else {
					tokens.get(i).setType(COMMAND.IGNORE);
				}
			}
		}

		if (tokens.size() > 1) {
			tokens.get(0).setEndOffset(tokens.get(1).getStartOffset()-1);
			if (tokens.get(0).getType() != COMMAND.IGNORE) {
				tokens.get(0).setType(classifyToken(new String(tokens.get(0).getData())));
			}
		} else {
			return;
		}
				
		/*
			iteration to split tokens to command and data since original tokens are on x01b boundaries
			this will split them and clasify appending new data tokens right after command to preserve seq
		*/
		int i = 0;
		while (i < tokens.size()) {
			Token[] tt = splitToken(tokens.get(i));
			if (tt != null) {
				tokens.remove(i);
				tokens.add(i, new Token());
				tokens.add(i, new Token());
				tokens.set(i, new Token(tt[0]));
				tokens.set(++i, new Token(tt[1]));
			}
			i++;
		}

		if (DEV_MODE) {
			i=0;
			while (i < tokens.size()) {
				Token t = tokens.get(i);
				System.out.print(t);
				if (t.getType() == COMMAND.DATA) {
					byte[] dta = t.getData();
					if (dta == null) 
						System.out.print(" \"\"");
					else 
						System.out.print(" \"" + (new String(dta)) + "\"");
				}
				if (t.getType() == COMMAND.MOVE_CURSOR)
					System.out.print(" " + (new String(t.getData())).substring(2,(new String(t.getData())).length()-2));
				System.out.println();
				i++;
			}
			for (i=0; i<26; i++) System.out.println();
		}		
	}
	
	// splits token to command and data tokens
	private Token[] splitToken(Token t) {
		String tokenString = new String(t.getData());
		Token[] tt = new Token[2];
		tt[0] = new Token();
		tt[1] = new Token();
		
		Matcher m = PS_CURSOR_HOME.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}
		
		m = PS_MOVE_CURSOR.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}
		
		m = PS_SAVE_CURSOR_ATTRIBS.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}	
		
		m = PS_RESTORE_CURSOR_ATTRIBS.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}
		
		m = PS_SELECT_GRAPHIC_RENDITION.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}
		
		m = PS_ERASE_IN_DISPLAY.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}
		
		m = PS_ERASE_IN_LINE.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}

		m = PS_ERASE_IN_LINE.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}
		
		m = PS_CURSOR_UP.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}
		
		m = PS_CURSOR_DOWN.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}		

		m = PS_CURSOR_LEFT.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}
		
		m = PS_CURSOR_RIGHT.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}		
		
/*		DO NOT HANDLE PS_IGNORE or most classification may fail. Left stub for future improvement if necessary
		m = PS_IGNORE.matcher(tokenString);
		if (m.matches()) {
			tt = form2Tokens(m, t);
			return tt;
		}
*/		
		return null;
	}
	
	public void setConsole(int cols, int rows, boolean wrap) {
		console = new Console(cols, rows, wrap);
	}
	
	/* reinitialize console */
	public void clear() {
		if (console == null) initConsole();
	}
	
	public void write(char[] text) {
		if (console == null) initConsole();
		console.write(text);
	}
	
	public void write(int x, int y, char[] text) {
		if (console == null) initConsole();
		console.write(x, y, text);
	}
	
	public void display() {
		if (console == null) return;
		console.display();
	}
	
	public void render() {
		if (console == null) initConsole();
		if (tokens.size() <2) return;
		for (Token t : tokens) {
			COMMAND cmd = t.getType();
			switch (cmd) {
				case CURSOR_HOME:
					console.cursorX = 0;
					console.cursorY = 0;
					break;
/*
				case COMMAND.ERASE_IN_DISPLAY:
				case COMMAND.SELECT_GRAPHIC_RENDITION:
				case COMMAND.IGNORE:
*/
				case ERASE_IN_LINE: {
					int param = 0;
					Matcher m = P_ERASE_IN_LINE.matcher(new String(t.getData()));
					if(m.matches()) {
						if (! m.group(2).equals("")) {
							param = Integer.parseInt(m.group(2));
						}
						switch (param) {
							case 0: {
								// From Cursor to End of Line
								char[] cc = (new String(new char[console.getColsCount() - console.cursorX]).replace('\0', ' ')).toCharArray();
								console.write(cc);
								break;
							}
							case 1: {
								//  From Beginning of Line to Cursor
								char[] cc = (new String(new char[console.cursorX]).replace('\0', ' ')).toCharArray();
								console.write(0, console.cursorY, cc);
								break;
							}
							case 2: {
								// Entire Line
								char[] cc = (new String(new char[console.getColsCount()]).replace('\0', ' ')).toCharArray();
								console.write(0, console.cursorY, cc);
								break;
							}
						}
					}
					break;
				}
				case MOVE_CURSOR: {
					int x=0; //column
					int y=0; //line
					Matcher m = P_MOVE_CURSOR.matcher(new String(t.getData()));
					if (m.matches()){
						x = Integer.parseInt(m.group(3));
						y = Integer.parseInt(m.group(2));
					}
					console.cursorY = (y>0?--y:y); // line offset to 0 index
					console.cursorX = (x>0?--x:x); // column offset to 0 index
					break; 
				}
				case CURSOR_DOWN: {
					// "^\\x1b\\[([(0-9]+)B$"
					int y = 1;
					Matcher m = P_CURSOR_DOWN.matcher(new String(t.getData()));
					if (m.matches()){ 
						y = Integer.parseInt(m.group(2));
					}
					console.cursorY += y;
					break; 
				}
				case CURSOR_UP: {
					// "^\\x1b\\[([(0-9]+)C$"
					int y = 1;
					Matcher m = P_CURSOR_DOWN.matcher(new String(t.getData()));
					if (m.matches()){ 
						y = Integer.parseInt(m.group(2));
					}
					console.cursorY -= y;
					break; 
				}			
				case SAVE_CURSOR_ATTRIBS:
					console.saveCursorPos();
					break;
				case RESTORE_CURSOR_ATTRIBS:
					console.restoreCursorPos();
					break;
				case DATA: {
					if (t.getStartOffset() == t.getEndOffset()) {
						break;
					}
					if (t.getData() != null) 
						console.write((new String(t.getData()).toCharArray()));
					break;
				}
			}
		}
	}
	
	@Override
	public String toString() {
		return ((console != null)?console.toString():"Console uinitialized.\n");
	}
	
/*********************** PRIVATE HELPER METHODS ***********************/	

	private Token[] form2Tokens(Matcher m, Token t){
		Token[] tt = new Token[2];
		tt[0] = new Token();
		tt[1] = new Token();
		
		tt[0].setStartOffset(t.getStartOffset());
		tt[0].setEndOffset(t.getStartOffset() + m.start(m.groupCount()) -1);
		tt[0].setType(t.getType());

		tt[1].setStartOffset(tt[0].getEndOffset()+1);
		tt[1].setEndOffset(t.getEndOffset());

		if (tt[1].getStartOffset() == tt[1].getEndOffset()) {
			return null;
		} else {
			tt[1].setType(COMMAND.DATA);
		}
		return tt;
	}
	
	// classifies token returning COMMAND type
	private COMMAND classifyToken(String token) {
		
		if (P_CURSOR_HOME.matcher(token).matches()) {
			return COMMAND.CURSOR_HOME;
		} else if (P_MOVE_CURSOR.matcher(token).matches()) {
			return COMMAND.MOVE_CURSOR;
		} else if (P_CURSOR_DOWN.matcher(token).matches()) {
			return COMMAND.CURSOR_DOWN;
		} else if (P_CURSOR_UP.matcher(token).matches()) {
			return COMMAND.CURSOR_UP;
		} else if (P_CURSOR_RIGHT.matcher(token).matches()) {
			return COMMAND.CURSOR_RIGHT;
		} else if (P_CURSOR_LEFT.matcher(token).matches()) {
			return COMMAND.CURSOR_LEFT;
		} else if (P_SAVE_CURSOR_ATTRIBS.matcher(token).matches()) {
			return COMMAND.SAVE_CURSOR_ATTRIBS;
		} else if (P_RESTORE_CURSOR_ATTRIBS.matcher(token).matches()) {
			return COMMAND.RESTORE_CURSOR_ATTRIBS;
		} else if (P_SELECT_GRAPHIC_RENDITION.matcher(token).lookingAt()) {
			return COMMAND.SELECT_GRAPHIC_RENDITION;
		} else if (P_ERASE_IN_DISPLAY.matcher(token).matches()) {
			return COMMAND.ERASE_IN_DISPLAY;
		} else if (P_ERASE_IN_LINE.matcher(token).matches()) {
			return COMMAND.ERASE_IN_LINE;
		} else if (P_IGNORE.matcher(token).matches()) {
			return COMMAND.IGNORE;
		}
		
		return COMMAND.IGNORE;
	}
	
	private void initConsole() {
		console = new Console();
	}
	
	private  final static String getDateTimeMillis()  {
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd_hh-mm-ss-S");
		df.setTimeZone(TimeZone.getTimeZone("UTC"));
		return df.format(new Date());
	}  
	
/*********************** DEMO ***********************/	
	
	/*
		Workflow:
		1 - instantiate Vt100ish class
		2 - call parse(InputStream), may throw IOException
		3 - get result by exexuting toString() on Vt100ish object
	*/
	public static void main(String [] args) {
		
		if (args.length != 1) {
			System.out.println("Filename not specified.");
			System.exit(0);
		}
		
		Vt100ish vt = new Vt100ish();
		
		try {
			InputStream in = new FileInputStream(args[0]);
			try {
				vt.parse(in);
			} finally {
				in.close();
			}
		} catch (IOException e) {
			// TODO: error handling
		}

		vt.render();
		vt.display();
/*		
		System.out.print("Press ENTER to continue");
		try {
			System.in.read();
		} catch (IOException e) {
			// TODO: error handling	
		}

		try {
			vt.parse(new ByteArrayInputStream("\033[H\033[2JHello World!!!".getBytes()));
		} catch (IOException e) {
			// TODO: error handling
		}
		
		vt.render();
		System.out.print(vt);
*/
	}
}
// Editor.scala
// Copyright (c) 2015 J. M. Spivey

import Undoable.Change

/** The editor state extended with methods for editor commands. */
class Editor extends Undoable {
    type Action = (Editor => Change)

    /** The buffer being edited. */
    private val ed = new EdBuffer

    /** The display. */
    private var display: Display = null
    
    /** Whether the command loop should continue */
    private var alive = true

    /** The default search string for searching the file */
    private var searchdefault = ""

    /** The default replace string for find and replace in the file */
    private var replacedefault = ""

    private var searchfor = ""
    private var replace = ""
    private var exit = false
    private var replacecount = 0
    private var searchcount = 0
    
    /** Show the buffer on a specified display */
    def activate(display: Display) {
        this.display = display
        display.show(ed)
        ed.register(display)
        ed.initDisplay()
    }

    /** Test if the buffer is modified */
    def isModified = ed.isModified

    /** Ask for confirmation if the buffer is not clean */
    def checkClean(action: String) = {
        if (! isModified) 
            true
        else {
            val question = 
                "Buffer modified -- really %s?".format(action)
            MiniBuffer.ask(display, question)
        }
    }

    /** Load a file into the buffer */
    def loadFile(fname: String) { ed.loadFile(fname) }

    /** Command: Move the cursor in the specified direction */
    def moveCommand(dir: Int) {
        var p = ed.point
        val row = ed.getRow(p)

        dir match {
            case Editor.LEFT => 
                if (p > 0) p -= 1
            case Editor.RIGHT =>
                if (p < ed.length) p += 1
            case Editor.UP =>
                p = ed.getPos(row-1, goalColumn())
            case Editor.DOWN =>
                p = ed.getPos(row+1, goalColumn())
            case Editor.HOME =>
                p = ed.getPos(row, 0)
            case Editor.END =>
                p = ed.getPos(row, ed.getLineLength(row)-1)
            case Editor.PAGEDOWN =>
                p = ed.getPos(row + Editor.SCROLL, 0)
                display.scroll(+Editor.SCROLL)
            case Editor.PAGEUP =>
                p = ed.getPos(row - Editor.SCROLL, 0)
                display.scroll(-Editor.SCROLL)
            case Editor.CTRLHOME =>
                p = ed.getPos(0,0)
            case Editor.CTRLEND =>
                p = ed.getPos(ed.numLines-1,ed.getLineLength(ed.numLines-1))
            case _ =>
                throw new Error("Bad direction")
        }

        ed.point = p
    }

    /** Command: Insert a character */
    def insertCommand(ch: Char): Change = {
        val p = ed.point
        ed.insert(p, ch)
        ed.point = p+1
        ed.setModified()
        new ed.AmalgInsertion(p, ch)
    }

    /** Command: Delete in a specified direction */
    def deleteCommand(dir: Int): Change = {
        var p = ed.point
        var ch: Char = 0

        dir match {
            case Editor.LEFT =>
                if (p == 0) { beep(); return null }
                p -= 1
                ch = ed.charAt(p)
                ed.deleteChar(p)
                ed.point = p
            case Editor.RIGHT =>
                if (p == ed.length) { beep(); return null }
                ch = ed.charAt(p)
                ed.deleteChar(p)
            case _ =>
                throw new Error("Bad direction")
        }

        ed.setModified()
        new ed.Deletion(p, ch)
    }
    
    def transposeCommand(): Change = {
        var p = ed.point

        if ((p == 0)||(p == ed.length)) { beep(); return null }
        else if (ed.getColumn(p) == (ed.getLineLength(ed.getRow(p))-1)) {
            p -= 1
            ed.transpose(p)
        }
        else {
            ed.transpose(p)
            ed.point = p + 1
        }
        ed.setModified()
        new ed.Transposition(p) 
    }

    def sRCommand(): Change = {
        var p = ed.point
        ed.deleteRange(p, searchfor.length)
        ed.insert(p, replace)
        replacecount += 1
        ed.setModified()
        new ed.SRChange(p, searchfor, replace)
    }

    def sRMassCommand(): Change = {
        var p = ed.point
        val s = searchfor.length
        var pos = p + s
        var max = 100
        var positions = new Array[Int](max)
        var i = 0
        while (pos != -1) {
            if (i == max) {
                max = 2*max
                val newpos = new Array[Int](max)
                Array.copy(positions, 0, newpos, 0, (max/2))
                positions = newpos
            }
            pos -= s
            positions(i) = pos
            ed.deleteRange(pos, s)
            ed.insert(pos, replace)
            i += 1
            pos = ed.indexOf(searchfor, pos + s)
        }
        replacecount += i
        searchcount += (i - 1)
        ed.setModified()
        new ed.SRMassChange(positions, i, searchfor, replace)
    }

    def quitSRCommand() {
        exit = true
    }

    def oneRCommand(): Change = {
        val chng = sRCommand()
        quitSRCommand()
        chng
    }

    def noCommand() { }

    /** Command: Save the file */
    def saveFileCommand() {
        val name = 
            MiniBuffer.readString(display, "Write file", ed.filename)
        if (name != null && name.length > 0)
            ed.saveFile(name)
    }

    /** Prompt for a file to read into the buffer.  */
    def replaceFileCommand() {
        if (! checkClean("overwrite")) return
        val name = 
            MiniBuffer.readString(display, "Read file", ed.filename)
        if (name != null && name.length > 0) {
            ed.point = 0
            ed.loadFile(name)
            ed.initDisplay()
            reset()
        }
    }

    def chooseOrigin() { display.chooseOrigin() }
    
    /** Quit, after asking about modified buffer */
    def quit() {
        if (checkClean("quit")) alive = false
    }

    /** Search the file for a specific sequence of characters */
    def search() {
        searchfor = MiniBuffer.readString(display, "Search for", searchdefault)
        if (searchfor != null && searchfor.length > 0) {
            searchdefault = searchfor
            var p = ed.point
            var res = ed.indexOfLoop (searchfor, p)
            if (res == -1) { MiniBuffer.message(display, s"No such string, '$searchfor', exists", searchfor) }
            else if (res == p) { MiniBuffer.message(display, s"This is the only occurrence of the string '$searchfor'", searchfor) }
            else { ed.point = res }
        }
    }

    /** Search the file for a sequence of characters and replace with another */
    def searchReplace() {
        searchfor = MiniBuffer.readString(display, "Search for", searchdefault)
        replace = MiniBuffer.readString(display, "Replace with", replacedefault)
        replacedefault = replace
        if (searchfor != null && searchfor.length > 0) {
            searchdefault = searchfor
            var p = ed.point
            exit = false
            replacecount = 0
            searchcount = 0
            while (!exit) {
                var res = ed.indexOf (searchfor, p)
                if (res == -1) { exit = true }
                else {
                    searchcount += 1
                    p = res - searchfor.length
                    ed.point = p
                    ed.update()
                    var ans = display.setMessage("Do you wish to replace this occurrence of '%s'?".format(searchfor))
                    var exitkeyloop = false
                    while (!exitkeyloop) {
                        val key = display.getKey()
                        Editor.srkeymap.find(key) match {
                            case Some(cmd) => {perform(cmd); exitkeyloop = true}
                            case None => beep()
                        }
                    }
                    p = res
                }
            }
            MiniBuffer.message(display, s"$searchcount occurrences of '$searchfor' were found, $replacecount were replaced", (searchcount, searchfor, replacecount))
        }
    }


    // Command execution protocol
    
    /** Goal column for vertical motion. */
    private var goal = -1
    private var prevgoal = 0
    
    /** Execute a command, wrapping it in actions common to all commands */
    def obey(cmd: Action): Change = {
        prevgoal = goal; goal = -1
        display.setMessage(null)
        val before = ed.getState
        val change = cmd(this)
        val after = ed.getState
        ed.update()
        ed.wrapChange(before, change, after)
    }
    
    /** The desired column for the cursor after an UP or DOWN motion */
    private def goalColumn() = {        
        /* Successive UP and DOWN commands share the same goal column,
         * but other commands cause it to be reset to the current column */
        if (goal < 0) {
            val p = ed.point
            goal = if (prevgoal >= 0) prevgoal else ed.getColumn(p)
        }
        
        goal
    }

    /** Beep */
    def beep() { display.beep() }

    /** Read keystrokes and execute commands */
    def commandLoop() {
        activate(display)

        while (alive) {
            val key = display.getKey()
            Editor.keymap.find(key) match {
                case Some(cmd) => perform(cmd)
                case None => beep()
            }
        }
    }
}

object Editor {
    /** Direction for use as argument to moveCommand or deleteCommand. */
    val LEFT = 1
    val RIGHT = 2
    val UP = 3
    val DOWN = 4
    val HOME = 5
    val END = 6
    val PAGEUP = 7
    val PAGEDOWN = 8
    val CTRLHOME = 9
    val CTRLEND = 10
    
    /** Amount to scroll the screen for PAGEUP and PAGEDOWN */
    val SCROLL = Display.HEIGHT - 3

    /** Possible value for damage. */
    val CLEAN = 0
    val REWRITE_LINE = 1
    val REWRITE = 2

    /** Main program for the entire Ewoks application. */
    def main(args: Array[String]) {
        if (args.length > 1) {
            Console.err.println("Usage: ewoks [file]")
            scala.sys.exit(2)
        }

        val terminal = new Terminal("EWOKS")
        terminal.activate()
        val app = new Editor()
        val display = new Display(terminal)
        app.activate(display)
        if (args.length > 0) app.loadFile(args(0))
        app.commandLoop()
        scala.sys.exit(0)
    }

    // This implicit conversion allows methods that return Unit to
    // be used as commands, not contributing to the undo history
    import scala.language.implicitConversions
    implicit def fixup(v: Unit): Change = null

    val srkeymap = Keymap[Editor => Change](
        32 -> (_.sRCommand),
        89 -> (_.sRCommand),
        121 -> (_.sRCommand),
        Display.ctrl('?') -> (_.noCommand),
        78 -> (_.noCommand),
        110 -> (_.noCommand),
        538 -> (_.quitSRCommand),
        46 -> (_.oneRCommand),
        33 -> (_.sRMassCommand))

    val keymap = Keymap[Editor => Change](
        Display.RETURN -> (_.insertCommand('\n')),
        Display.RIGHT -> (_.moveCommand(RIGHT)),
        Display.LEFT -> (_.moveCommand(LEFT)),
        Display.UP -> (_.moveCommand(UP)),
        Display.DOWN -> (_.moveCommand(DOWN)),
        Display.HOME -> (_.moveCommand(HOME)),
        Display.END -> (_.moveCommand(END)),
        Display.PAGEUP -> (_.moveCommand(PAGEUP)),
        Display.PAGEDOWN -> (_.moveCommand(PAGEDOWN)),
        Display.CTRLHOME -> (_.moveCommand(CTRLHOME)),
        Display.CTRLEND -> (_.moveCommand(CTRLEND)),
        Display.ctrl('?') -> (_.deleteCommand(LEFT)),
        Display.DEL -> (_.deleteCommand(RIGHT)),
        Display.ctrl('A') -> (_.moveCommand(HOME)),
        Display.ctrl('B') -> (_.moveCommand(LEFT)),
        Display.ctrl('D') -> (_.deleteCommand(RIGHT)),
        Display.ctrl('E') -> (_.moveCommand(END)),
        Display.ctrl('F') -> (_.moveCommand(RIGHT)),
        Display.ctrl('G') -> (_.beep),
        Display.ctrl('L') -> (_.chooseOrigin),
        Display.ctrl('N') -> (_.moveCommand(DOWN)),
        Display.ctrl('P') -> (_.moveCommand(UP)),
        Display.ctrl('Q') -> (_.quit),
        Display.ctrl('R') -> (_.replaceFileCommand),
        Display.ctrl('S') -> (_.search),
        Display.ctrl('T') -> (_.transposeCommand),
        Display.ctrl('W') -> (_.saveFileCommand),
        Display.ctrl('U') -> (_.searchReplace),
        Display.ctrl('Y') -> (_.redo),
        Display.ctrl('Z') -> (_.undo))

    for (ch <- Display.printable)
        keymap += ch -> (_.insertCommand(ch.toChar))
}

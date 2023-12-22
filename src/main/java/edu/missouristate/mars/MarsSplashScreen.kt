package edu.missouristate.mars

import java.awt.*
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow

/**
 * Produces MARS splash screen.<br></br>
 * Adapted from [...](http://www.java-tips.org/content/view/1267/2/)<br></br>
 */
class MarsSplashScreen(private val duration: Int) : JWindow() {
    /**
     * A simple little method to show a title screen in the center
     * of the screen for the amount of time given in the constructor
     */
    fun showSplash() {
        val content = ImageBackgroundPanel()
        this.contentPane = content

        // Set the window's bounds, centering the window
        // Wee bit of a hack.  I've hardcoded the image dimensions of 
        // MarsSurfacePathfinder.jpg, because obtaining them via
        // getHeight() and getWidth() is not trivial -- it is possible
        // that at the time of the call the image has not completed
        // loading so the Image object doesn't know how big it is.
        // So observers are involved -- see the API.
        val width = 390
        val height = 215
        val tk = Toolkit.getDefaultToolkit()
        val screen = tk.screenSize
        val x = (screen.width - width) / 2
        val y = (screen.height - height) / 2
        setBounds(x, y, width, height)

        // Build the splash screen
        val title = JLabel("MARS: Mips Assembler and Runtime Simulator", JLabel.CENTER)
        val copyrightLineOne = JLabel(
            "<html><br><br>Version " + Globals.version + " Copyright (c) " + Globals.copyrightYears + "</html>",
            JLabel.CENTER
        )
        val copyrightLineTwo = JLabel("<html><br><br>" + Globals.copyrightHolders + "</html>", JLabel.CENTER)
        title.font = Font("Sans-Serif", Font.BOLD, 16)
        title.foreground = Color.black
        copyrightLineOne.font = Font("Sans-Serif", Font.BOLD, 14)
        copyrightLineTwo.font = Font("Sans-Serif", Font.BOLD, 14)
        copyrightLineOne.foreground = Color.white
        copyrightLineTwo.foreground = Color.white

        content.add(title, BorderLayout.NORTH)
        content.add(copyrightLineOne, BorderLayout.CENTER)
        content.add(copyrightLineTwo, BorderLayout.SOUTH)

        // Display it
        isVisible = true
        // Wait a little while, maybe while loading resources
        try {
            Thread.sleep(duration.toLong())
        } catch (ignored: Exception) {
        }
        isVisible = false
    }

    internal class ImageBackgroundPanel : JPanel() {
        private var image: Image? = null

        init {
            try {
                image = ImageIcon(
                    Toolkit.getDefaultToolkit()
                        .getImage(this.javaClass.getResource(Globals.imagesPath + "MarsSurfacePathfinder.jpg"))
                ).image
            } catch (e: Exception) {
                println(e) /*handled in paintComponent()*/
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (image != null) g.drawImage(image, 0, 0, this.width, this.height, this)
        }
    }
}

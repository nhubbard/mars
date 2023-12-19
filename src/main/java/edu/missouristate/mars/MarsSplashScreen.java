package edu.missouristate.mars;

import javax.swing.*;
import java.awt.*;

/**
 * Produces MARS splash screen.<br>
 * Adapted from <a href="http://www.java-tips.org/content/view/1267/2/">...</a><br>
 */

public class MarsSplashScreen extends JWindow {
    private final int duration;

    public MarsSplashScreen(int d) {
        duration = d;
    }

    /**
     * A simple little method to show a title screen in the center
     * of the screen for the amount of time given in the constructor
     */
    public void showSplash() {
        ImageBackgroundPanel content = new ImageBackgroundPanel();
        this.setContentPane(content);

        // Set the window's bounds, centering the window
        // Wee bit of a hack.  I've hardcoded the image dimensions of 
        // MarsSurfacePathfinder.jpg, because obtaining them via
        // getHeight() and getWidth() is not trival -- it is possible
        // that at the time of the call the image has not completed
        // loading so the Image object doesn't know how big it is.
        // So observers are involved -- see the API.
        int width = 390;
        int height = 215;
        Toolkit tk = Toolkit.getDefaultToolkit();
        Dimension screen = tk.getScreenSize();
        int x = (screen.width - width) / 2;
        int y = (screen.height - height) / 2;
        setBounds(x, y, width, height);

        // Build the splash screen
        JLabel title = new JLabel("MARS: Mips Assembler and Runtime Simulator", JLabel.CENTER);
        JLabel copyrt1 = new JLabel("<html><br><br>Version " + Globals.version + " Copyright (c) " + Globals.copyrightYears + "</html>", JLabel.CENTER);
        JLabel copyrt2 = new JLabel("<html><br><br>" + Globals.copyrightHolders + "</html>", JLabel.CENTER);
        title.setFont(new Font("Sans-Serif", Font.BOLD, 16));
        title.setForeground(Color.black);
        copyrt1.setFont(new Font("Sans-Serif", Font.BOLD, 14));
        copyrt2.setFont(new Font("Sans-Serif", Font.BOLD, 14));
        copyrt1.setForeground(Color.white);
        copyrt2.setForeground(Color.white);

        content.add(title, BorderLayout.NORTH);
        content.add(copyrt1, BorderLayout.CENTER);
        content.add(copyrt2, BorderLayout.SOUTH);

        // Display it
        setVisible(true);
        // Wait a little while, maybe while loading resources
        try {
            Thread.sleep(duration);
        } catch (Exception ignored) {}
        setVisible(false);
    }

    @SuppressWarnings("ThrowablePrintedToSystemOut")
    static class ImageBackgroundPanel extends JPanel {
        Image image;

        public ImageBackgroundPanel() {
            try {
                image = new ImageIcon(Toolkit.getDefaultToolkit().getImage(this.getClass().getResource(Globals.imagesPath + "MarsSurfacePathfinder.jpg"))).getImage();
            } catch (Exception e) {
                System.out.println(e); /*handled in paintComponent()*/
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image != null) g.drawImage(image, 0, 0, this.getWidth(), this.getHeight(), this);
        }
    }
}

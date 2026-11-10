package edu.missouristate.mars.desktop.tools;

import edu.missouristate.mars.ApplicationInfo;
import edu.missouristate.mars.datapath.DatapathDiagram;
import edu.missouristate.mars.datapath.DatapathDiagram.Kind;
import edu.missouristate.mars.datapath.DatapathDiagram.Palette;
import edu.missouristate.mars.datapath.DatapathDiagram.Wire;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serial;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.Timer;

class UnitAnimation extends JPanel implements ActionListener {
  @Serial private static final long serialVersionUID = -2681757800180958534L;
  private static final int PWIDTH = 1000; // size of this panel
  private static final int PHEIGHT = 574;
  private final GraphicsConfiguration gc;
  private int counter; // verify then remove.
  private boolean justStarted; // flag to start movement

  private int indexX; // counter of screen position
  private int indexY;
  private boolean xIsMoving, yIsMoving; // flag for mouse movement.

  // private Vertex[][] inputGraph;
  private ArrayList<ArrayList<Vertex>> outputGraph;
  private final ArrayList<Vertex> vertexList;
  private ArrayList<Vertex> vertexTraversed;
  // Screen Label variables


  private String instructionCode;
  private final int register = 1;
  private final int control = 2;
  private final int aluControl = 3;
  private final int alu = 4;
  private final int datapatTypeUsed;
  private Boolean cursorInReg;

  private Graphics2D g2d;

  private BufferedImage datapath;

  static class Vertex {
    private int numIndex;
    private int init;
    private int end;
    private int current;
    private String name;
    public static final int movingUpside = 1;
    public static final int movingDownside = 2;
    public static final int movingLeft = 3;
    public static final int movingRight = 4;
    public final int direction;
    public int oppositeAxis;
    private boolean isMovingXaxis;
    private Color color;
    private boolean first_interaction;
    private boolean active;
    private final boolean isText;
    private final List<Integer> targetVertex;

    public Vertex(int index, Wire wire, Palette palette) {
      this.numIndex = index;
      this.init = wire.start();
      this.current = init;
      this.end = wire.end();
      this.name = wire.name();
      this.oppositeAxis = wire.axis();
      this.isMovingXaxis = wire.horizontal();
      this.first_interaction = true;
      this.active = false;
      this.isText = wire.label();
      this.color = new Color(wire.rgb(palette));
      this.direction =
          isMovingXaxis
              ? (init < end ? movingLeft : movingRight)
              : (init < end ? movingUpside : movingDownside);
      this.targetVertex = wire.targets();
    }

    public int getDirection() {
      return direction;
    }

    public boolean isText() {
      return this.isText;
    }

    public List<Integer> getTargetVertex() {
      return targetVertex;
    }

    public int getNumIndex() {
      return numIndex;
    }

    public void setNumIndex(int numIndex) {
      this.numIndex = numIndex;
    }

    public int getInit() {
      return init;
    }

    public void setInit(int init) {
      this.init = init;
    }

    public int getEnd() {
      return end;
    }

    public void setEnd(int end) {
      this.end = end;
    }

    public int getCurrent() {
      return current;
    }

    public void setCurrent(int current) {
      this.current = current;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public int getOppositeAxis() {
      return oppositeAxis;
    }

    public void setOppositeAxis(int oppositeAxis) {
      this.oppositeAxis = oppositeAxis;
    }

    public boolean isMovingXaxis() {
      return isMovingXaxis;
    }

    public void setMovingXaxis(boolean isMovingXaxis) {
      this.isMovingXaxis = isMovingXaxis;
    }

    public Color getColor() {
      return color;
    }

    public void setColor(Color color) {
      this.color = color;
    }

    public boolean isFirst_interaction() {
      return first_interaction;
    }

    public void setFirst_interaction(boolean first_interaction) {
      this.first_interaction = first_interaction;
    }

    public boolean isActive() {
      return active;
    }

    public void setActive(boolean active) {
      this.active = active;
    }
  }

  public UnitAnimation(String instructionBinary, int datapathType) {
    datapatTypeUsed = datapathType;
    Boolean cursorInIM = false;
    Boolean cursorInALU = false;
    Boolean cursorInDataMem = false;
    DecimalFormat df = new DecimalFormat("0.0"); // 1 dp
    GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
    // for reporting accl. memory usage
    GraphicsDevice gd = ge.getDefaultScreenDevice();
    gc = ge.getDefaultScreenDevice().getDefaultConfiguration();

    int accelMemory = gd.getAvailableAcceleratedMemory(); // in bytes
    setBackground(Color.white);
    setPreferredSize(new Dimension(PWIDTH, PHEIGHT));

    // load and initialise the images
    initImages();

    vertexList = new ArrayList<>();
    counter = 0;
    justStarted = true;
    instructionCode = instructionBinary;

    // declaration of labels definition.

    int countRegLabel = 400;
    int countALULabel = 380;
    int countPCLabel = 380;
    loadWires();
  } // end of ImagesTests()

  private void loadWires() {
    Kind kind = switch (datapatTypeUsed) {
      case register -> Kind.REGISTERS;
      case control -> Kind.CONTROL;
      case aluControl -> Kind.ALU_CONTROL;
      default -> null;
    };
    if (kind == null) {
      return;
    }
    var palette = MipsXray.paletteFor(instructionCode, kind == Kind.ALU_CONTROL);
    for (var wire : DatapathDiagram.forKind(kind).wires()) {
      vertexList.add(new Vertex(vertexList.size(), wire, palette));
    }
    outputGraph = new ArrayList<>();
    vertexTraversed = new ArrayList<>();
    for (Vertex vertex : vertexList) {
      var targets = new ArrayList<Vertex>();
      for (int target : vertex.getTargetVertex()) {
        targets.add(vertexList.get(target));
      }
      outputGraph.add(targets);
    }
    vertexList.getFirst().setActive(true);
    vertexTraversed.add(vertexList.getFirst());
  }

  // set the initial state of the variables that controls the animation, and start the timer that
  // triggers the animation.
  public void startAnimation(String codeInstruction) {
    instructionCode = codeInstruction;
    // config variables
    // velocity of frames in ms
    int PERIOD = 8;
    new Timer(PERIOD, this).start(); // start timer
    this.repaint();
  }

  // initialize the image of datapath.
  private void initImages() {
    try {
      BufferedImage im;
      if (datapatTypeUsed == register) {
        im = ImageIO.read(getClass().getResource(ApplicationInfo.imagesPath + "register.png"));
      } else if (datapatTypeUsed == control) {
        im = ImageIO.read(getClass().getResource(ApplicationInfo.imagesPath + "control.png"));
      } else if (datapatTypeUsed == aluControl) {
        im = ImageIO.read(getClass().getResource(ApplicationInfo.imagesPath + "ALUcontrol.png"));
      } else {
        im = ImageIO.read(getClass().getResource(ApplicationInfo.imagesPath + "alu.png"));
      }

      int transparency = im.getColorModel().getTransparency();
      datapath = gc.createCompatibleImage(im.getWidth(), im.getHeight(), transparency);
      g2d = datapath.createGraphics();
      g2d.drawImage(im, 0, 0, null);
      g2d.dispose();
    } catch (IOException e) {
      System.out.println(
          "Load Image error for "
              + getClass().getResource(ApplicationInfo.imagesPath + "register.png")
              + ":\n"
              + e);
    }
  }

  public void updateDisplay() {
    this.repaint();
  }

  public void actionPerformed(ActionEvent e)
        // triggered by the timer: update, repaint
      {
    if (justStarted) {
      justStarted = false;
    }
    if (xIsMoving) {
      indexX++;
    }
    if (yIsMoving) {
      indexY--;
    }
    repaint();
  }

  public void paintComponent(Graphics g) {
    super.paintComponent(g);
    g2d = (Graphics2D) g;
    // use antialiasing
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    // smoother (and slower) image transformations  (e.g. for resizing)
    g2d.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g2d = (Graphics2D) g;
    drawImage(g2d, datapath, 0, 0, null);
    executeAnimation(g);
    counter = (counter + 1) % 100;
    g2d.dispose();
  }

  private void drawImage(Graphics2D g2d, BufferedImage im, int x, int y, Color c) {
    if (im == null) {
      g2d.setColor(c);
      g2d.fillOval(x, y, 20, 20);
      g2d.setColor(Color.black);
      g2d.drawString("   ", x, y);
    } else {
      g2d.drawImage(im, x, y, this);
    }
  }

  // draw lines.
  // method to draw the lines that run from left to right.
  public void printTrackLtoR(Vertex v) {
    int size;
    int[] track;
    size = v.getEnd() - v.getInit();
    track = new int[size];
    for (int i = 0; i < size; i++) {
      track[i] = v.getInit() + i;
    }
    if (v.isActive()) {
      v.setFirst_interaction(false);
      for (int i = 0; i < size; i++) {
        if (track[i] <= v.getCurrent()) {
          g2d.setColor(v.getColor());
          g2d.fillRect(track[i], v.getOppositeAxis(), 3, 3);
        }
      }
      if (v.getCurrent() == track[size - 1]) {
        v.setActive(false);
      }
      v.setCurrent(v.getCurrent() + 1);
    } else if (!v.isFirst_interaction()) {
      for (int i = 0; i < size; i++) {
        g2d.setColor(v.getColor());
        g2d.fillRect(track[i], v.getOppositeAxis(), 3, 3);
      }
    }
  }

  // method to draw the lines that run from right to left.
  // public boolean printTrackRtoL(int init, int end ,int currentIndex, Graphics2D g2d, Color color,
  // int otherAxis,
  //		 boolean active, boolean firstInteraction){
  public void printTrackRtoL(Vertex v) {
    int size;
    int[] track;
    size = v.getInit() - v.getEnd();
    track = new int[size];

    for (int i = 0; i < size; i++) {
      track[i] = v.getInit() - i;
    }

    if (v.isActive()) {
      v.setFirst_interaction(false);
      for (int i = 0; i < size; i++) {
        if (track[i] >= v.getCurrent()) {
          g2d.setColor(v.getColor());
          g2d.fillRect(track[i], v.getOppositeAxis(), 3, 3);
        }
      }
      if (v.getCurrent() == track[size - 1]) {
        v.setActive(false);
      }

      v.setCurrent(v.getCurrent() - 1);
    } else if (!v.isFirst_interaction()) {
      for (int i = 0; i < size; i++) {
        g2d.setColor(v.getColor());
        g2d.fillRect(track[i], v.getOppositeAxis(), 3, 3);
      }
    }
  }

  // method to draw the lines that run from down to top.
  // public boolean printTrackDtoU(int init, int end ,int currentIndex, Graphics2D g2d, Color color,
  // int otherAxis,
  //		 boolean active, boolean firstInteraction){
  public void printTrackDtoU(Vertex v) {
    int size;
    int[] track;

    if (v.getInit() > v.getEnd()) {
      size = v.getInit() - v.getEnd();
      track = new int[size];
      for (int i = 0; i < size; i++) {
        track[i] = v.getInit() - i;
      }
    } else {
      size = v.getEnd() - v.getInit();
      track = new int[size];
      for (int i = 0; i < size; i++) {
        track[i] = v.getInit() + i;
      }
    }

    if (v.isActive()) {
      v.setFirst_interaction(false);
      for (int i = 0; i < size; i++) {
        if (track[i] >= v.getCurrent()) {
          g2d.setColor(v.getColor());
          g2d.fillRect(v.getOppositeAxis(), track[i], 3, 3);
        }
      }
      if (v.getCurrent() == track[size - 1]) {
        v.setActive(false);
      }
      v.setCurrent(v.getCurrent() - 1);

    } else if (!v.isFirst_interaction()) {
      for (int i = 0; i < size; i++) {
        g2d.setColor(v.getColor());
        g2d.fillRect(v.getOppositeAxis(), track[i], 3, 3);
      }
    }
  }

  // method to draw the lines that run from top to down.
  // public boolean printTrackUtoD(int init, int end ,int currentIndex, Graphics2D g2d, Color color,
  // int otherAxis,
  //		 boolean active,  boolean firstInteraction){
  public void printTrackUtoD(Vertex v) {

    int size;
    int[] track;
    size = v.getEnd() - v.getInit();
    track = new int[size];

    for (int i = 0; i < size; i++) {
      track[i] = v.getInit() + i;
    }

    if (v.isActive()) {
      v.setFirst_interaction(false);
      for (int i = 0; i < size; i++) {
        if (track[i] <= v.getCurrent()) {
          g2d.setColor(v.getColor());
          g2d.fillRect(v.getOppositeAxis(), track[i], 3, 3);
        }
      }
      if (v.getCurrent() == track[size - 1]) {
        v.setActive(false);
      }
      v.setCurrent(v.getCurrent() + 1);
    } else if (!v.isFirst_interaction()) {
      for (int i = 0; i < size; i++) {
        g2d.setColor(v.getColor());
        g2d.fillRect(v.getOppositeAxis(), track[i], 3, 3);
      }
    }
  }

  // convert binnary value to integer.
  public String parseBinToInt(String code) {
    int value = 0;

    for (int i = code.length() - 1; i >= 0; i--) {
      if ("1".equals(code.substring(i, i + 1))) {
        value = value + (int) Math.pow(2, code.length() - i - 1);
      }
    }

    return Integer.toString(value);
  }

  // set and execute the information about the current position of each line of information in the
  // animation,
  // verifies the previous status of the animation and increment the position of each line that
  // interconnect the unit function.
  @SuppressWarnings("StatementWithEmptyBody")
  private void executeAnimation(Graphics g) {
    g2d = (Graphics2D) g;
    Vertex vert;
    for (int i = 0; i < vertexTraversed.size(); i++) {
      vert = vertexTraversed.get(i);
      if (vert.isMovingXaxis) {
        if (vert.getDirection() == Vertex.movingLeft) {
          printTrackLtoR(vert);
          if (!vert.isActive()) {
            int j = vert.getTargetVertex().size();
            Vertex tempVertex;
            for (int k = 0; k < j; k++) {
              tempVertex = outputGraph.get(vert.getNumIndex()).get(k);
              boolean hasThisVertex = false;
              for (Vertex vertex : vertexTraversed) {
                if (tempVertex.getNumIndex() == vertex.getNumIndex()) {
                  hasThisVertex = true;
                  break;
                }
              }
              if (!hasThisVertex) {
                outputGraph.get(vert.getNumIndex()).get(k).setActive(true);
                vertexTraversed.add(outputGraph.get(vert.getNumIndex()).get(k));
              }
            }
          }
        } else {
          printTrackRtoL(vert);
          if (!vert.isActive()) {
            int j = vert.getTargetVertex().size();
            Vertex tempVertex;
            for (int k = 0; k < j; k++) {
              tempVertex = outputGraph.get(vert.getNumIndex()).get(k);
              boolean hasThisVertex = false;
              for (Vertex vertex : vertexTraversed) {
                if (tempVertex.getNumIndex() == vertex.getNumIndex()) {
                  hasThisVertex = true;
                  break;
                }
              }
              if (!hasThisVertex) {
                outputGraph.get(vert.getNumIndex()).get(k).setActive(true);
                vertexTraversed.add(outputGraph.get(vert.getNumIndex()).get(k));
              }
            }
          }
        }
      } // end of condition of X axis
      else {
        if (vert.getDirection() == Vertex.movingDownside) {
          if (vert.isText) {
            ;
          } else {
            printTrackDtoU(vert);
          }

          if (!vert.isActive()) {
            int j = vert.getTargetVertex().size();
            Vertex tempVertex;
            for (int k = 0; k < j; k++) {
              tempVertex = outputGraph.get(vert.getNumIndex()).get(k);
              boolean hasThisVertex = false;
              for (Vertex vertex : vertexTraversed) {
                if (tempVertex.getNumIndex() == vertex.getNumIndex()) {
                  hasThisVertex = true;
                  break;
                }
              }
              if (!hasThisVertex) {
                outputGraph.get(vert.getNumIndex()).get(k).setActive(true);
                vertexTraversed.add(outputGraph.get(vert.getNumIndex()).get(k));
              }
            }
          }

        } else {
          printTrackUtoD(vert);
          if (!vert.isActive()) {
            int j = vert.getTargetVertex().size();
            Vertex tempVertex;
            for (int k = 0; k < j; k++) {
              tempVertex = outputGraph.get(vert.getNumIndex()).get(k);
              boolean hasThisVertex = false;
              for (Vertex vertex : vertexTraversed) {
                if (tempVertex.getNumIndex() == vertex.getNumIndex()) {
                  hasThisVertex = true;
                  break;
                }
              }
              if (!hasThisVertex) {
                outputGraph.get(vert.getNumIndex()).get(k).setActive(true);
                vertexTraversed.add(outputGraph.get(vert.getNumIndex()).get(k));
              }
            }
          }
        }
      }
    }
  }
}

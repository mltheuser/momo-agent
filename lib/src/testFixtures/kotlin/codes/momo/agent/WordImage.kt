package codes.momo.agent

import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Renders [word] as large black text on white into a PNG at [path] — a
 * planted visual fact for vision cases: the word reaches the model only if
 * the image itself does.
 */
public fun writeWordImage(path: Path, word: String) {
    val image = BufferedImage(400, 160, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, image.width, image.height)
    graphics.color = Color.BLACK
    graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 64)
    graphics.drawString(word, 30, 100)
    graphics.dispose()
    check(ImageIO.write(image, "png", path.toFile())) { "no PNG writer available" }
}
